# post-command-service as a GraalVM native executable: starts in well under a
# second and needs a fraction of the JVM's memory. JAVA_VERSION must match
# <java.version> in pom.xml.
ARG JAVA_VERSION=25

# Maven itself, to run on the GraalVM JDK below (the GraalVM image has no Maven)
FROM maven:3.9-eclipse-temurin-${JAVA_VERSION} AS maven

# Stage 1: Spring AOT processing and native compilation with Oracle GraalVM.
# native-image needs several GB of memory and a few minutes.
FROM container-registry.oracle.com/graalvm/native-image:${JAVA_VERSION} AS builder
COPY --from=maven /usr/share/maven /usr/share/maven
ENV PATH=/usr/share/maven/bin:${PATH}
# Extra native-image options, e.g. --build-arg NATIVE_IMAGE_OPTIONS=-J-Xmx5g to cap its
# memory on a small machine (by default it takes most of what the builder has)
ARG NATIVE_IMAGE_OPTIONS=""
ENV NATIVE_IMAGE_OPTIONS=${NATIVE_IMAGE_OPTIONS}
WORKDIR /app
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -Pnative dependency:go-offline
COPY src ./src
# maven.test.skip also skips the AOT processing of the tests, which the image does not need
RUN --mount=type=cache,target=/root/.m2 mvn -B -Pnative -Dmaven.test.skip=true native:compile

# Stage 2: Run. The executable links glibc and zlib dynamically: Oracle Linux 9,
# the base of the builder image, has the same versions.
FROM container-registry.oracle.com/os/oraclelinux:9-slim
WORKDIR /app
COPY --from=builder /app/target/post-command-service /app/post-command-service
# Not root, matching the chart's podSecurityContext
USER 10001:10001
EXPOSE 8082
ENTRYPOINT ["/app/post-command-service"]
