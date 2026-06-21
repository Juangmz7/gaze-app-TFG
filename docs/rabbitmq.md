# RabbitMQ Infrastructure Documentation

## What it does
RabbitMQ acts as the central message broker for the microservice architecture, enabling asynchronous communication and event-driven choreography between different services. The infrastructure setup includes both the RabbitMQ server itself and an initialization service (`rabbitmq-init`) that ensures the necessary exchanges are pre-created.

## Packages used
- **Base Image:** `rabbitmq:4.3-management-alpine` (includes the RabbitMQ management UI plugin).
- **Init Script Image:** A custom Python container (`rabbitmq-init`) utilizing the `pika` library to connect to the broker and declare configurations on startup.

## Why
- **Decoupling Services:** Using a message broker allows services to communicate without tight coupling or synchronous HTTP dependencies, improving system resilience and scalability.
- **Management UI:** The Alpine management image provides an intuitive web interface (exposed on port 15672) to monitor queues, exchanges, and message rates during development and operations.
- **Automated Initialization:** The `init_exchanges.py` script automatically declares the `x.auth.events` (a `topic` exchange) with retry logic. This ensures that the exchange exists as soon as RabbitMQ is ready, allowing Keycloak to publish events successfully even if the consuming microservices haven't started or bound their queues yet.
