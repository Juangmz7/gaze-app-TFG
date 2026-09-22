# Recommendation Service

## Overview
This service is responsible for generating personalized feeds and recommendations for users based on their social graph and interactions. It is built using Python and FastAPI, following a **Domain-Driven Design (DDD)** and **Hexagonal Architecture** approach where applicable.

## Packages
The service consists of several key modules:
- **Pipeline**: Machine learning or recommendation pipelines.
- **User / Follow / Block / Post**: Read models and local domains to synchronize state from other services.
- **RabbitMQ**: Message broker integration for consuming events from `social-service` and `post-command-service`.
- **Observability**: Logging and tracing configurations.
- **Shared**: Common utilities.

## Key Design Choices
1. **Python & RabbitMQ**: Chosen for its ecosystem of data science and machine learning libraries, along with high-performance asynchronous API capabilities.
2. **Event Consumption**: It maintains its own read models of users, posts, and follows by listening to RabbitMQ events.
3. **Hexagonal Concepts**: Uses `model`/`entity`, `usecase`/`command`, and `repository` patterns to separate business logic from infrastructure concerns.

Explore the `docs` folder inside each package for detailed documentation.
