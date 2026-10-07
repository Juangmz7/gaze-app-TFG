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

`infra/local/` holds the **local development / testing** environment as two independent Compose files that share `infra/local/.env` and the `infra_net` network:

- `infra/local/compose.yaml` — backing services (databases, broker, IAM, observability). Enough on its own if you run the services from your IDE.
- `infra/local/app/compose.yaml` — the four microservices, built from each service's `Dockerfile`, to test them containerized.

Technical decisions:

- **One command per stack.** All backing services are defined together so any developer can stand up the full environment with a single `docker compose up -d`, guaranteeing environment parity across machines.
- **Dedicated bridge network (`infra_net`).** Containers resolve each other by service name. The network has a fixed name so the apps compose can join it as `external`. Ports are published on `127.0.0.1` only (Redis and Mongo have no hardening and Docker bypasses the host firewall).
- **Healthchecks + explicit startup ordering.** Services that depend on another being ready (e.g. Keycloak on Postgres and on RabbitMQ's exchanges existing) use `depends_on: condition: service_healthy` / `service_completed_successfully` instead of arbitrary sleeps. `depends_on` does not work across compose files, so the apps use `restart: unless-stopped` and retry until their dependencies are up.
- **Polyglot persistence, one database per bounded context.** Each service owns its data store rather than sharing a schema:
  - `postgres-keycloak` — Keycloak's own state (realms, users, clients).
  - `social_postgres` — social-service's relational source of truth.
  - `social_neo4j` — social-service's graph store for follow/friend-of-friend traversal.
  - `post_command_postgres` — post-command-service's write model.
  - `post_query_mongo` — post-query-service's read models.
  - `recommendation_postgres` — recommendation-service's features and embeddings (`pgvector/pgvector:pg16`, needed for `CREATE EXTENSION vector`).
  - `redis` — shared cache.

  Database containers also get a hyphenated network alias (`social-neo4j`, `post-command-postgres`, ...) because `java.net.URI` rejects underscores in hostnames.
- **Keycloak as centralized IAM, customized to publish events.** A custom `infra/local/keycloak/Dockerfile` bakes in the `keycloak-event-listener-rabbitmq` plugin so auth events (register, login, etc.) are emitted to RabbitMQ instead of requiring services to poll Keycloak. See `docs/keycloak.md`.
- **RabbitMQ as the event backbone, pre-provisioned on boot.** A short-lived `rabbitmq-init` container (Python + `pika`) declares the required exchanges (e.g. `x.auth.events`) as soon as RabbitMQ is healthy, so publishers never race consumers that haven't bound queues yet. Keycloak and `rabbitmq-init` always use `rabbitmq:5672` internally; `RABBITMQ_PORT` only changes the port published on the host. See `docs/rabbitmq.md`.
- **Grafana LGTM (`grafana-lgtm`) for observability.** A single all-in-one image providing Grafana + Loki + Tempo + Mimir/Prometheus, exposing an OTLP endpoint (`4317` gRPC / `4318` HTTP) that services send traces/metrics/logs to, and a UI on `3000`.
- **Azure Blob Storage is emulated with Azurite.** `post-command-service` uploads post media to Azure Blob Storage in production; locally it talks to the `azurite` container (well-known `devstoreaccount1` credentials, see `post-command-service/src/main/resources/application-dev.yaml`).
- **Secrets stay out of git.** `infra/local/.env` is gitignored; `infra/local/.env_template` lists every variable both stacks need with empty/default values. Each service also has its own `.env_template` for service-specific secrets when run outside Docker.

## Local setup

### Prerequisites
- Docker + Docker Compose (with BuildKit, the default in modern Docker, for the apps' build caches)

### 1. Configure environment
Run these steps sequentially in the same shell, starting from the repository root. After the first command, stay in `infra/local` for the remaining commands.

```bash
cd infra/local
cp .env_template .env
```
Fill in `.env` with real values (DB passwords, Keycloak admin credentials, RabbitMQ credentials, etc.) — nothing in `.env_template` ships with real secrets.

### 2. Start the infrastructure stack
```bash
docker compose up -d
docker compose ps    # wait until everything is healthy; rabbitmq-init ends as "Exited (0)"
```
This brings up, in dependency order: `postgres-keycloak` → `rabbitmq` → `rabbitmq-init` → `keycloak`, plus every service database, `redis`, `azurite` and `grafana-lgtm`.

> If `infra_recommendation_postgres_data` was created with the old `postgres:16-alpine` image, remove it (`docker volume rm infra_recommendation_postgres_data`) to avoid collation warnings (musl vs glibc) with the pgvector image.

### 3. (Optional) Start the microservices in containers
```bash
docker compose --env-file .env -f app/compose.yaml up -d --build
```
`--env-file .env` is required: Compose otherwise looks for `.env` next to `app/compose.yaml`.

- **Spring services** (`social-service`, `post-command-service`) run with the `dev` profile (`ddl-auto=update`, sampling 1.0). Its `localhost` endpoints are overridden through relaxed-binding env vars (`SPRING_DATASOURCE_URL`, `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT`, ...), exporting OTLP over HTTP to `grafana-lgtm:4318`.
- **JWT validation**: the issuer is `http://localhost:<KC_PORT>/realms/auth-service` (tokens obtained from the host), while the signing keys are fetched from `keycloak:8080` inside the network.
- **post-query-service** (Go) is built with the repository root as context, since it resolves `common-packages/go-utils` through `go.work`.
- **recommendation-service** runs the Alembic migrations, then the FastStream worker under `opentelemetry-instrument` (OTLP gRPC to `grafana-lgtm:4317`), on CPU (`MODEL_DEVICE=cpu`). The embedding model is downloaded at build time.
- Credentials containing `@`, `/` or `:` must be URL-encoded in the URIs built from them (`DATABASE_URL`, `RABBITMQ_URL`).

### 4. Default ports

| Service | Port(s) |
|---|---|
| Keycloak | `8080` |
| RabbitMQ (AMQP / management UI) | `5672` / `15672` |
| Postgres (social-service) | `5433` |
| Postgres (post-command-service) | `5434` |
| Postgres + pgvector (recommendation-service) | `5435` |
| Neo4j (HTTP / Bolt) | `7474` / `7687` |
| Redis | `6379` |
| MongoDB (post-query-service) | `27017` |
| Grafana LGTM (UI / OTLP gRPC / OTLP HTTP) | `3000` / `4317` / `4318` |
| Azurite (blob / queue / table) | `10000` / `10001` / `10002` |
| social-service (container) | `8081` |
| post-command-service (container) | `8082` |
| post-query-service (container) | `8083` |

Ports are overridable via the corresponding `*_PORT` variables in `infra/local/.env`.

### 5. First-run checks

| What | How |
|---|---|
| Everything healthy | `docker compose ps` |
| Exchanges/queues created | RabbitMQ UI at `http://localhost:15672` |
| pgvector available | `docker exec recommendation_postgres psql -U <user> -d <db> -c "CREATE EXTENSION IF NOT EXISTS vector;"` |
| Traces arriving | Grafana at `http://localhost:3000` → Explore → Tempo |
| Python traces missing | `docker logs recommendation_service` and look for OTLP exporter errors |

### 6. Bring the stacks down
```bash
docker compose --env-file .env -f app/compose.yaml down
docker compose down        # keep volumes (data persists)
docker compose down -v     # also wipe volumes (fresh state)
```

### 7. Run a service from the IDE
Each service is independently runnable once the infra stack is up — see that service's own `init.sh`, `test.sh`, and `docs/<service>.md` for language-specific setup (Maven wrapper, Go modules, Python venv).
