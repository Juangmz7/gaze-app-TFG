package com.app.postcommandservice.shared.infrastructure.mapper;

import java.util.Comparator;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.util.ClassUtils;

import com.app.postcommandservice.shared.infrastructure.events.EventMessage;

/**
 * Native image hints for the events and commands {@link JsonMapper} writes to the outbox (and
 * reads back). They are serialized by hand, so Spring AOT does not see them the way it sees
 * {@code @RequestBody} or {@code @RabbitHandler} types: without these hints Jackson fails in
 * the native image with "Record components not available" and no event is ever published.
 * Every {@link EventMessage} of the service is registered for data binding (record
 * components, nested payload types, enums), read from the classpath at AOT build time.
 */
class EventMessageRuntimeHints implements RuntimeHintsRegistrar {

    static final String BASE_PACKAGE = "com.app.postcommandservice";

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        new BindingReflectionHintsRegistrar().registerReflectionHints(hints.reflection(),
                eventMessageTypes(classLoader).toArray(Class<?>[]::new));
    }

    static Set<Class<?>> eventMessageTypes(ClassLoader classLoader) {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.setResourceLoader(new DefaultResourceLoader(classLoader));
        scanner.addIncludeFilter(new AssignableTypeFilter(EventMessage.class));

        var types = new TreeSet<Class<?>>(Comparator.comparing(Class::getName));
        for (var candidate : scanner.findCandidateComponents(BASE_PACKAGE)) {
            types.add(ClassUtils.resolveClassName(candidate.getBeanClassName(), classLoader));
        }
        return types;
    }
}
