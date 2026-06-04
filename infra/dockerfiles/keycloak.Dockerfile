FROM quay.io/keycloak/keycloak:25.0

# Copy the RabbitMQ Plugin into the providers directory
COPY ./keycloak/providers/ /opt/keycloak/providers/

ENV KC_DB=postgres

# This bakes the plugin into the server and optimizes boot times
RUN /opt/keycloak/bin/kc.sh build

# 5. Set the default command for Production
# 'start' is the production command (replaces 'start-dev')
# '--optimized' tells Keycloak to use the build we just created in step 4
# '--import-realm' ensures your JSON config is loaded
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start-dev", "--optimized", "--import-realm"]