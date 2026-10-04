# TFG-code

Microservices backend for the project: event-driven social/content platform split into independently deployable services, backed by a shared local infrastructure stack.

## Services

| Service | Stack | Responsibility |
|---|---|---|
| `social-service` | Java / Spring Boot | User profiles, follow graph, blocking, outbox-pattern event publishing |
| `post-command-service` | Java / Spring Boot | Post writes (create/update/delete, likes, comments, shares, views), media uploads via Azure Blob Storage |
| `post-query-service` | Go | Post read models |
| `recommendation-service` | Python / FastAPI | Personalized feeds, event-driven read models synced from RabbitMQ |
| `common-packages` | Go / Java | Shared libraries used across services |

Each service has its own `docs/<service>.md` (architecture and design decisions) and `init.sh` / `test.sh` scripts. This README only covers the **shared infrastructure** that all services depend on.

## Infrastructure: technical decisions

The `infra/` directory holds a single Docker Compose stack (`infra/compose.yaml`) providing every backing service needed for local development.

- **Single compose file, one command bootstrap.** All backing services (databases, broker, IAM, observability) are defined together so any developer can stand up the full environment with `docker compose up -d`, guaranteeing environment parity across machines.
- **Dedicated bridge network (`infra_net`).** Containers resolve each other by service name and aren't exposed to each other over the host network; only the ports a developer actually needs are published to localhost.
- **Healthchecks + explicit startup ordering.** Services that depend on another being ready (e.g. Keycloak on Postgres and on RabbitMQ's exchanges existing) use `depends_on: condition: service_healthy` / `service_completed_successfully` instead of arbitrary sleeps, so the stack comes up reliably on first try.
- **Polyglot persistence, one database per bounded context.** Each service owns its data store rather than sharing a schema:
  - `postgres-keycloak` — Keycloak's own state (realms, users, clients).
  - `social_postgres` — social-service's relational source of truth.
  - `social_neo4j` — social-service's graph store for follow/friend-of-friend traversal.
  - `post_query_mongo` — post-query-service's read models.
  - `redis` — shared cache.
- **Keycloak as centralized IAM, customized to publish events.** A custom `infra/keycloak/Dockerfile` bakes in the `keycloak-event-listener-rabbitmq` plugin so auth events (register, login, etc.) are emitted to RabbitMQ instead of requiring services to poll Keycloak. See `docs/keycloak.md`.
- **RabbitMQ as the event backbone, pre-provisioned on boot.** A short-lived `rabbitmq-init` container (Python + `pika`) declares the required exchanges (e.g. `x.auth.events`) as soon as RabbitMQ is healthy, so publishers never race consumers that haven't bound queues yet. See `docs/rabbitmq.md`.
- **Grafana LGTM (`grafana-lgtm`) for observability.** A single all-in-one image providing Grafana + Loki + Tempo + Mimir/Prometheus, exposing an OTLP endpoint (`4317` gRPC / `4318` HTTP) that services send traces/metrics/logs to, and a UI on `3000`. Chosen over wiring the four components separately to keep local observability to one container.
- **Azure Blob Storage is emulated locally, not containerized in this stack.** `post-command-service` uploads post media to Azure Blob Storage in production; locally it talks to an **Azurite** emulator run via `npx azurite` (not part of `infra/compose.yaml`), listening on the Azurite default ports (`10000` blob / `10001` queue / `10002` table). See `post-command-service/.env_template` and `application-dev.yaml` for the matching connection settings (well-known Azurite devstoreaccount1 credentials).
- **Secrets stay out of git.** `infra/.env` is gitignored; `infra/.env_template` lists every variable the stack needs with empty/default values. Each service also has its own `.env_template` for service-specific secrets (e.g. Azure Storage connection details).

## Local setup

### Prerequisites
- Docker + Docker Compose
- Node.js (for `npx azurite`), only needed if you're working on `post-command-service`'s media upload flow

### 1. Configure environment
```bash
cd infra
cp .env_template .env
```
Fill in `.env` with real values (DB passwords, Keycloak admin credentials, RabbitMQ credentials, etc.) — nothing in `.env_template` ships with real secrets.

### 2. Start the infrastructure stack
```bash
cd infra
docker compose up -d
```
This brings up, in dependency order: `postgres-keycloak` → `rabbitmq` → `rabbitmq-init` → `keycloak`, plus `social_postgres`, `social_neo4j`, `redis`, `post_query_mongo`, and `grafana-lgtm`.

Check everything is healthy:
```bash
docker compose ps
```

### 3. (Optional) Start the Azurite emulator
Only needed if you're running `post-command-service` with media upload functionality locally:
```bash
npx azurite --silent --location ./azurite-data --debug ./azurite-data/debug.log
```

### 4. Default ports

| Service | Port(s) |
|---|---|
| Keycloak | `8080` |
| RabbitMQ (AMQP / management UI) | `5672` / `15672` |
| Postgres (Keycloak) | `5432` |
| Postgres (social-service) | `5433` |
| Neo4j (HTTP / Bolt) | `7474` / `7687` |
| Redis | `6379` |
| MongoDB (post-query-service) | `27017` |
| Grafana LGTM (UI / OTLP gRPC / OTLP HTTP) | `3000` / `4317` / `4318` |
| Azurite (blob / queue / table) | `10000` / `10001` / `10002` |

Ports are overridable via the corresponding `*_PORT` variables in `infra/.env`.

### 5. Bring the stack down
```bash
cd infra
docker compose down        # keep volumes (data persists)
docker compose down -v     # also wipe volumes (fresh state)
```

### 6. Run a service
Each service is independently runnable once the infra stack is up — see that service's own `init.sh`, `test.sh`, and `docs/<service>.md` for language-specific setup (Maven wrapper, Go modules, Python venv).
