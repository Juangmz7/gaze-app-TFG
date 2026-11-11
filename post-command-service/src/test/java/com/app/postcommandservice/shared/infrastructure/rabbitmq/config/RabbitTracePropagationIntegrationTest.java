package com.app.postcommandservice.shared.infrastructure.rabbitmq.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import com.rabbitmq.client.GetResponse;
import com.rabbitmq.client.LongString;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.BaggageInScope;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Verifies that the application's own {@link RabbitTemplate} and listener container factory
 * propagate the W3C trace context over RabbitMQ, so a message published inside a trace is
 * consumed in that same trace (also across retry attempts), and that the context travels as
 * plain AMQP string headers that the Go (Watermill) and Python (FastStream) consumers read.
 *
 * <p>{@code management.tracing.export.enabled=false} (the test default) swaps the propagator for a
 * no-op one, so export is re-enabled here and only the OTLP exporter itself is switched off.
 */
@ActiveProfiles("test")
@Import(RabbitTracePropagationIntegrationTest.TracingProbeContainers.class)
@AutoConfigureTracing
@SpringBootTest(properties = {
        "management.tracing.export.enabled=true",
        "management.tracing.export.otlp.enabled=false",
        "management.tracing.sampling.probability=1.0",
        "management.tracing.baggage.remote-fields=" + RabbitTracePropagationIntegrationTest.BAGGAGE_KEY
})
class RabbitTracePropagationIntegrationTest {

    static final String BAGGAGE_KEY = "tracing-probe-baggage";

    private static final String PROBE_QUEUE = "q.post-command-service.test.tracing.probe";
    private static final String RETRY_PROBE_QUEUE = "q.post-command-service.test.tracing.retry-probe";
    private static final String RAW_PROBE_QUEUE = "q.post-command-service.test.tracing.raw-probe";

    private static final Pattern TRACEPARENT =
            Pattern.compile("^00-(?<traceId>[0-9a-f]{32})-(?<parentId>[0-9a-f]{16})-(?<flags>[0-9a-f]{2})$");

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private ObservationRegistry observationRegistry;

    @Autowired
    private Tracer tracer;

    @Autowired
    private TracingProbeListener probeListener;

    @BeforeEach
    void setUp() {
        probeListener.reset();
        rabbitTemplate.execute(channel -> channel.queuePurge(RAW_PROBE_QUEUE));
    }

    @Test
    void shouldPublishW3cTraceparentAsStringHeaderAndConsumeInSameTrace() throws Exception {
        var producerTraceId = publishInsideObservation(PROBE_QUEUE, "probe");

        var received = probeListener.received.poll(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

        assertThat(received).as("message consumed by the probe listener").isNotNull();
        assertThat(received.traceparent()).isInstanceOf(String.class);
        var traceparent = TRACEPARENT.matcher((String) received.traceparent());
        assertThat(traceparent.matches()).as("W3C traceparent: %s", received.traceparent()).isTrue();
        assertThat(traceparent.group("traceId")).isEqualTo(producerTraceId);
        assertThat(traceparent.group("flags")).isEqualTo("01");

        assertThat(received.baggage()).isInstanceOf(String.class);
        assertThat((String) received.baggage()).contains(BAGGAGE_KEY + "=probe-value");

        assertThat(received.consumerTraceId())
                .as("listener runs in the producer's trace")
                .isEqualTo(producerTraceId);
    }

    @Test
    void shouldSendTraceContextAsAmqpLongStringHeadersOnTheWire() {
        var producerTraceId = publishInsideObservation(RAW_PROBE_QUEUE, "raw-probe");

        var response = new AtomicReference<GetResponse>();
        await().atMost(TIMEOUT).until(() -> {
            response.set(rabbitTemplate.execute(channel -> channel.basicGet(RAW_PROBE_QUEUE, true)));
            return response.get() != null;
        });

        Map<String, Object> headers = response.get().getProps().getHeaders();
        assertThat(headers).containsKeys("traceparent", "baggage");
        // AMQP field type 'S' (long string): what Watermill and FastStream decode as a plain string.
        assertThat(headers.values()).allSatisfy(value -> assertThat(value).isInstanceOf(LongString.class));
        assertThat(headers.get("traceparent").toString()).startsWith("00-" + producerTraceId + "-");
    }

    @Test
    void shouldKeepEveryRetryAttemptInTheProducerTrace() throws Exception {
        var producerTraceId = publishInsideObservation(RETRY_PROBE_QUEUE, "retry-probe");

        var first = probeListener.retryAttempts.poll(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        var second = probeListener.retryAttempts.poll(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

        assertThat(first).as("first (failing) attempt").isNotNull();
        assertThat(second).as("retried attempt from the RetryInterceptor advice chain").isNotNull();
        assertThat(List.of(first.consumerTraceId(), second.consumerTraceId()))
                .containsOnly(producerTraceId);
        assertThat(second.consumerSpanId())
                .as("retries run inside the same listener observation/span")
                .isEqualTo(first.consumerSpanId());
    }

    private String publishInsideObservation(String queue, Object payload) {
        var traceId = new AtomicReference<String>();
        Observation.createNotStarted("tracing-probe.publish", observationRegistry).observe(() -> {
            var span = tracer.currentSpan();
            assertThat(span).as("observation opened a span").isNotNull();
            traceId.set(span.context().traceId());
            try (BaggageInScope ignored = tracer.createBaggageInScope(BAGGAGE_KEY, "probe-value")) {
                rabbitTemplate.convertAndSend("", queue, payload);
            }
        });
        return traceId.get();
    }

    record Received(Object traceparent, Object baggage, String consumerTraceId, String consumerSpanId) {
    }

    static class TracingProbeListener {

        private final Tracer tracer;
        final BlockingQueue<Received> received = new LinkedBlockingQueue<>();
        final BlockingQueue<Received> retryAttempts = new LinkedBlockingQueue<>();
        private final AtomicInteger retryCalls = new AtomicInteger();

        TracingProbeListener(Tracer tracer) {
            this.tracer = tracer;
        }

        void reset() {
            received.clear();
            retryAttempts.clear();
            retryCalls.set(0);
        }

        @RabbitListener(queues = PROBE_QUEUE)
        void onProbe(Message message) {
            received.add(capture(message));
        }

        @RabbitListener(queues = RETRY_PROBE_QUEUE)
        void onRetryProbe(Message message) {
            retryAttempts.add(capture(message));
            if (retryCalls.incrementAndGet() == 1) {
                // IllegalStateException is retryable (only AmqpRejectAndDontRequeue/IllegalArgument are excluded).
                throw new IllegalStateException("fail the first attempt to trigger a retry");
            }
        }

        private Received capture(Message message) {
            var span = tracer.currentSpan();
            return new Received(
                    message.getMessageProperties().getHeaders().get("traceparent"),
                    message.getMessageProperties().getHeaders().get("baggage"),
                    span == null ? null : span.context().traceId(),
                    span == null ? null : span.context().spanId());
        }
    }

    /**
     * The infrastructure the application context needs, minus the Grafana LGTM container from
     * {@code TestcontainersConfiguration}: no span is exported here, and LGTM is the slowest container
     * to start.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class TracingProbeContainers {

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgresContainer() {
            return new PostgreSQLContainer(DockerImageName.parse("postgres:latest"));
        }

        @Bean
        @ServiceConnection
        RabbitMQContainer rabbitContainer() {
            return new RabbitMQContainer(DockerImageName.parse("rabbitmq:latest"));
        }

        @Bean
        @ServiceConnection(name = "redis")
        GenericContainer<?> redisContainer() {
            return new GenericContainer<>(DockerImageName.parse("redis:latest")).withExposedPorts(6379);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TracingProbeConfig {

        @Bean
        Queue tracingProbeQueue() {
            return QueueBuilder.durable(PROBE_QUEUE).build();
        }

        @Bean
        Queue tracingRetryProbeQueue() {
            return QueueBuilder.durable(RETRY_PROBE_QUEUE).build();
        }

        @Bean
        Queue tracingRawProbeQueue() {
            return QueueBuilder.durable(RAW_PROBE_QUEUE).build();
        }

        @Bean
        TracingProbeListener tracingProbeListener(Tracer tracer) {
            return new TracingProbeListener(tracer);
        }
    }
}
