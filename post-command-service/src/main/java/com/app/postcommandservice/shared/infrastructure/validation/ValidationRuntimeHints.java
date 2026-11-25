package com.app.postcommandservice.shared.infrastructure.validation;

import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.Set;
import java.util.TreeSet;

import jakarta.validation.Constraint;
import jakarta.validation.Valid;
import org.springframework.aot.hint.ExecutableMode;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.util.ClassUtils;

/**
 * Native image hints for the methods Hibernate Validator invokes reflectively: every method of
 * the service carrying a Bean Validation constraint ({@code @AssertTrue}, {@code @NotNull},
 * composed constraints, ...) or {@code @Valid}. Spring AOT registers the record accessors of
 * {@code @RequestBody} types, but not other getters: an {@code @AssertTrue boolean hasX()} on a
 * request record fails in the native image with MissingReflectionRegistrationError and the
 * request ends in a 500. The methods are read from the classpath at AOT build time, so new
 * request types are covered without keeping lists by hand.
 */
class ValidationRuntimeHints implements RuntimeHintsRegistrar {

    static final String BASE_PACKAGE = "com.app.postcommandservice";

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        for (Method method : constrainedMethods(classLoader)) {
            hints.reflection().registerMethod(method, ExecutableMode.INVOKE);
        }
    }

    /** Methods declared by the service's classes with a constraint or {@code @Valid} on them. */
    static Set<Method> constrainedMethods(ClassLoader classLoader) {
        var methods = new TreeSet<Method>(Comparator.comparing(Method::toGenericString));
        for (Class<?> type : types(classLoader)) {
            for (Method method : type.getDeclaredMethods()) {
                if (isConstrained(method)) {
                    methods.add(method);
                }
            }
        }
        return methods;
    }

    private static boolean isConstrained(Method method) {
        // Meta-annotations and repeatable containers (@Pattern.List) included.
        var annotations = MergedAnnotations.from(method);
        return annotations.isPresent(Constraint.class) || annotations.isPresent(Valid.class);
    }

    /** Every top-level or static nested class of the service, abstract ones and interfaces too. */
    private static Set<Class<?>> types(ClassLoader classLoader) {
        var scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                return beanDefinition.getMetadata().isIndependent();
            }
        };
        scanner.setResourceLoader(new DefaultResourceLoader(classLoader));
        scanner.addIncludeFilter((reader, factory) -> true);

        var types = new TreeSet<Class<?>>(Comparator.comparing(Class::getName));
        for (var candidate : scanner.findCandidateComponents(BASE_PACKAGE)) {
            types.add(ClassUtils.resolveClassName(candidate.getBeanClassName(), classLoader));
        }
        return types;
    }
}
