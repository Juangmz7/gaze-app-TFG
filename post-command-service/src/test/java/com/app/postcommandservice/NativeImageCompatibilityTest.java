package com.app.postcommandservice;

import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards against code that runs on the JVM but breaks the GraalVM native image, where the
 * failure only shows when the image starts (the JVM tests cannot catch it otherwise).
 */
class NativeImageCompatibilityTest {

    private static final String BASE_PACKAGE = "com.app.postcommandservice";

    /**
     * {@code @Lazy} on a class-typed injection point (typically {@code @Lazy Self self} to call
     * the bean's own {@code @Transactional} methods) makes Spring build a lazy-resolution CGLIB
     * proxy at run time. A native image can only load proxy classes generated at build time, and
     * that one clashes with the bean's transactional proxy: the context fails with a
     * ClassCastException in {@code setCallbacks}. Inject {@code ObjectProvider<Self>} instead.
     */
    @Test
    void shouldNotInjectClassesLazily() {
        var scanner = new ClassPathScanningCandidateComponentProvider(true);
        List<String> offenders = new ArrayList<>();

        for (var candidate : scanner.findCandidateComponents(BASE_PACKAGE)) {
            var type = ClassUtils.resolveClassName(candidate.getBeanClassName(), getClass().getClassLoader());
            for (var constructor : type.getDeclaredConstructors()) {
                for (Parameter parameter : constructor.getParameters()) {
                    if (parameter.isAnnotationPresent(Lazy.class) && !parameter.getType().isInterface()) {
                        offenders.add(type.getSimpleName() + " constructor: @Lazy " + parameter.getType().getSimpleName());
                    }
                }
            }
            ReflectionUtils.doWithFields(type,
                    field -> offenders.add(type.getSimpleName() + "." + field.getName() + ": @Lazy "
                            + field.getType().getSimpleName()),
                    field -> field.isAnnotationPresent(Lazy.class) && !field.getType().isInterface());
        }

        assertThat(offenders)
                .as("@Lazy class injection breaks the native image; use ObjectProvider<T> instead")
                .isEmpty();
    }
}
