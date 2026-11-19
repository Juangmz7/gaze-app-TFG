# Keycloak Infrastructure Documentation

## What it does
Keycloak serves as the central Identity and Access Management (IAM) provider for the microservice architecture. It handles user authentication, authorization, and federation. In this specific infrastructure setup, Keycloak is customized to emit authentication and user management events directly to a RabbitMQ message broker.

## Packages used
- **Base Image:** `quay.io/keycloak/keycloak:25.0.6`
- **Database:** PostgreSQL (via `postgres:16-alpine` in the Compose stack) for persistent storage of realms, users, and clients.
- **Plugins:** `keycloak-event-listener-rabbitmq` (version 3.0.5 by aznamier) - A custom plugin downloaded and baked into the image during the build process to publish events.

## Why
- **Centralized IAM:** Offloads the complexity of securing microservices and managing user credentials.
- **Event-Driven Integration:** By baking in the `keycloak-to-rabbit.jar` plugin, Keycloak can instantly notify the rest of the system about critical events (e.g., user registration, login, profile updates) in an asynchronous, decoupled manner. This prevents other microservices from having to constantly poll Keycloak for state changes and enables robust choreographies.
  - *Note:* The specific events dispatched to RabbitMQ are strictly determined by the `enabledEventTypes` array in the Realm configuration (e.g. `REGISTER`, `LOGIN`). If an event type is omitted, the plugin will not dispatch it.
- **Optimized Image:** The Dockerfile runs `/opt/keycloak/bin/kc.sh build` with the plugin included, ensuring faster boot times by optimizing the image beforehand.

## Realm clients
The `auth-service` realm is defined in `infra/local/keycloak/realms/auth_realm.json` (local compose stack) and `infra/k8s/charts/keycloak/files/auth-service-realm.json` (k8s `KeycloakRealmImport`). Keep both copies identical.

- **`mobile-app`**: public client, authorization code flow with PKCE (`pkce.code.challenge.method: S256`); direct access grants disabled.
  - `redirectUris`: `myapp://callback` (plus the Postman callback outside prod; in k8s the list is overridden per environment by `realm.clientRedirectUris`).
  - `post.logout.redirect.uris`: `myapp://callback` only. The app sends it as `post_logout_redirect_uri` together with `id_token_hint` to `<issuer>/protocol/openid-connect/logout`, and Keycloak redirects back to the app instead of leaving the logout page open. Never use `+` (every login redirect URI, including the Postman one) or `*` (any URI). If this attribute is missing, Keycloak 25 imports it as `+`.
- The k8s realm import only runs when the realm does not exist yet. Changes to these files reach an existing cluster realm only if the realm is re-imported (delete the realm, then sync) or the same change is made in the admin console (Clients > mobile-app > Valid post logout redirect URIs).
