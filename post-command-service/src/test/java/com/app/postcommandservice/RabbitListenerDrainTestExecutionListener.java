package com.app.postcommandservice;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

/**
 * Keeps the application's own RabbitMQ listeners from carrying work from one test into the next.
 *
 * <p>Flow tests trigger commands that this service publishes through the outbox and then consumes
 * itself (for example {@code POST /views} publishes a {@code ProcessPostViewCommand} that the view
 * listener turns into a post view and a {@code PostViewedEvent}). The test only waits for what it
 * asserts, so on a slow machine that listener can still be running when {@code @AfterEach} empties
 * the tables, and the rows it commits afterwards are counted by the next test.
 *
 * <p>Right after each test method (before {@code @AfterEach}) the listener containers are stopped,
 * which waits for the message in flight, and their queues are purged; they start again before the
 * next test method of a context that uses them. Stopping waits for each consumer's receive
 * timeout (1 s by default), so it is lowered here and the containers stop in parallel, keeping the
 * cost per test small. Registered for every Spring test in
 * {@code META-INF/spring.factories}; contexts without RabbitMQ listeners are left alone.
 */
public class RabbitListenerDrainTestExecutionListener implements TestExecutionListener, Ordered {

    private static final long RECEIVE_TIMEOUT_MS = 50;
    private static final long STOP_TIMEOUT_SECONDS = 30;

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void beforeTestMethod(TestContext testContext) {
        var containers = containers(testContext);
        var stopped = containers.stream().filter(container -> !container.isRunning()).toList();
        if (stopped.isEmpty()) {
            return;
        }
        // Whatever reached the queues after the drain belongs to the previous test.
        purge(testContext.getApplicationContext(), stopped);
        stopped.forEach(AbstractMessageListenerContainer::start);
    }

    @Override
    public void afterTestExecution(TestContext testContext) {
        var containers = containers(testContext);
        if (containers.isEmpty()) {
            return;
        }
        stopAll(containers);
        purge(testContext.getApplicationContext(), containers);
    }

    /** {@code stop(Runnable)} returns at once and runs the callback when the container is idle. */
    private static void stopAll(List<AbstractMessageListenerContainer> containers) {
        var stopped = new CountDownLatch(containers.size());
        containers.forEach(container -> container.stop(stopped::countDown));
        try {
            if (!stopped.await(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("RabbitMQ listeners still busy after " + STOP_TIMEOUT_SECONDS + " s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping the RabbitMQ listeners", e);
        }
    }

    private static List<AbstractMessageListenerContainer> containers(TestContext testContext) {
        if (!testContext.hasApplicationContext()) {
            return List.of();
        }
        var registry = testContext.getApplicationContext()
                .getBeanProvider(RabbitListenerEndpointRegistry.class)
                .getIfAvailable();
        if (registry == null) {
            return List.of();
        }
        var containers = registry.getListenerContainers().stream()
                .filter(AbstractMessageListenerContainer.class::isInstance)
                .map(AbstractMessageListenerContainer.class::cast)
                .toList();
        // Applies to the consumers created on the next start.
        containers.stream()
                .filter(SimpleMessageListenerContainer.class::isInstance)
                .map(SimpleMessageListenerContainer.class::cast)
                .forEach(container -> container.setReceiveTimeout(RECEIVE_TIMEOUT_MS));
        return containers;
    }

    private static void purge(ApplicationContext context, List<AbstractMessageListenerContainer> containers) {
        var admin = context.getBeanProvider(AmqpAdmin.class).getIfAvailable();
        if (admin == null) {
            return;
        }
        containers.stream()
                .flatMap(container -> Arrays.stream(container.getQueueNames()))
                .distinct()
                .forEach(queue -> admin.purgeQueue(queue, false));
    }
}
