# Infrastructure (Docker Compose) Documentation

## What it does
The `infra` directory contains the foundational Docker Compose stack required to run the backing services for the microservice architecture. It defines and orchestrates the database, identity provider, and message broker.

## Packages used
- **PostgreSQL (`postgres:16-alpine`):** A lightweight, relational database serving as the persistence layer for Keycloak.
- **Keycloak (`./keycloak/Dockerfile`):** Custom build of Keycloak that includes RabbitMQ event publishing capabilities.
- **RabbitMQ (`rabbitmq:4.3-management-alpine`):** The message broker for asynchronous communication.
- **RabbitMQ Init (`./rabbitmq/Dockerfile`):** A Python-based utility container that connects to RabbitMQ on startup and configures default exchanges.

## Why
- **Reproducibility:** A single `compose.yaml` file allows any developer to spin up the entire foundational infrastructure with a single command (`docker compose up -d`), ensuring environment consistency.
- **Network Isolation:** All containers run within a dedicated custom bridge network (`infra_net`), allowing them to communicate internally by container name without exposing unnecessary ports to the host system.
- **Healthchecks and Dependencies:** The compose file utilizes Docker healthchecks (e.g., `pg_isready` for Postgres and `rabbitmq-diagnostics` for RabbitMQ). Services are orchestrated with `depends_on: condition: service_healthy`, ensuring that Keycloak only starts after PostgreSQL is ready and the RabbitMQ exchanges have been successfully initialized.
