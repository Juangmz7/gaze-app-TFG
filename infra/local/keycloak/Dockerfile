FROM quay.io/keycloak/keycloak:25.0.6

# Download the keycloak-to-rabbitmq event listener plugin
# Source: https://github.com/aznamier/keycloak-event-listener-rabbitmq
ARG KK_TO_RMQ_VERSION=3.0.5
ADD --chmod=644 https://github.com/aznamier/keycloak-event-listener-rabbitmq/releases/download/${KK_TO_RMQ_VERSION}/keycloak-to-rabbit-${KK_TO_RMQ_VERSION}.jar /opt/keycloak/providers/keycloak-to-rabbit.jar

ENV KC_DB=postgres
# Build-time options: the Keycloak Operator (infra/k8s) starts this image with
# --optimized and probes /health/* on the management port, so they must be
# baked in here.
ENV KC_HEALTH_ENABLED=true
ENV KC_METRICS_ENABLED=true

# This bakes the plugin into the server and optimizes boot times
RUN /opt/keycloak/bin/kc.sh build

# 'start' is the production command (replaces 'start-dev')
# '--optimized' (add when setting production)tells Keycloak to use the build we just created
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start-dev", "--import-realm"]
