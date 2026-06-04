FROM quay.io/keycloak/keycloak:25.0.6

# Download the keycloak-to-rabbitmq event listener plugin
# Source: https://github.com/aznamier/keycloak-event-listener-rabbitmq
ARG KK_TO_RMQ_VERSION=3.0.5
ADD --chmod=644 https://github.com/aznamier/keycloak-event-listener-rabbitmq/releases/download/${KK_TO_RMQ_VERSION}/keycloak-to-rabbit-${KK_TO_RMQ_VERSION}.jar /opt/keycloak/providers/keycloak-to-rabbit.jar

ENV KC_DB=postgres

# This bakes the plugin into the server and optimizes boot times
RUN /opt/keycloak/bin/kc.sh build

# 'start' is the production command (replaces 'start-dev')
# '--optimized' tells Keycloak to use the build we just created
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start-dev", "--optimized", "--import-realm"]