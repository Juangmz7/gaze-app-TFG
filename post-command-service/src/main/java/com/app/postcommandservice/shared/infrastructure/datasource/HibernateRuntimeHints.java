package com.app.postcommandservice.shared.infrastructure.datasource;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.ExecutableMode;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

/**
 * Native image hints for what Hibernate does reflectively with this service's entities and
 * that Spring AOT does not register on its own. They are read from the entities at AOT build
 * time, so new entities are covered without keeping lists by hand:
 * <ul>
 *   <li>the array of every {@code @Id} type: for multi-id loading, MultiIdEntityLoaderArrayParam
 *       instantiates it while building the session factory (startup fails otherwise);</li>
 *   <li>{@code @EntityListeners} classes and lifecycle callbacks ({@code @PrePersist}, ...),
 *       which Hibernate invokes reflectively on every insert/update;</li>
 *   <li>the type of every {@code @JdbcTypeCode(SqlTypes.JSON)} attribute, which Hibernate
 *       (de)serializes with Jackson.</li>
 * </ul>
 */
class HibernateRuntimeHints implements RuntimeHintsRegistrar {

    static final String ENTITY_PACKAGE = "com.app.postcommandservice";

    private static final List<Class<? extends Annotation>> LIFECYCLE_CALLBACKS = List.of(
            PrePersist.class, PostPersist.class, PreUpdate.class, PostUpdate.class,
            PreRemove.class, PostRemove.class, PostLoad.class);

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        var entities = entities(classLoader);

        for (Class<?> idType : idTypes(entities)) {
            hints.reflection().registerType(idType.arrayType());
        }

        for (Class<?> listener : entityListeners(entities)) {
            hints.reflection().registerType(listener, MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
            for (Method callback : lifecycleCallbacks(listener)) {
                hints.reflection().registerMethod(callback, ExecutableMode.INVOKE);
            }
        }
        for (Class<?> entity : entities) {
            for (Method callback : lifecycleCallbacks(entity)) {
                hints.reflection().registerMethod(callback, ExecutableMode.INVOKE);
            }
        }

        new BindingReflectionHintsRegistrar().registerReflectionHints(hints.reflection(),
                jsonAttributeTypes(entities).toArray(Class<?>[]::new));
    }

    /** Every {@code @Entity} and {@code @Embeddable} of the service. */
    static Set<Class<?>> entities(ClassLoader classLoader) {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.setResourceLoader(new DefaultResourceLoader(classLoader));
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(Embeddable.class));

        var entities = sortedSet();
        for (var candidate : scanner.findCandidateComponents(ENTITY_PACKAGE)) {
            entities.add(ClassUtils.resolveClassName(candidate.getBeanClassName(), classLoader));
        }
        return entities;
    }

    /** The type of every {@code @Id} field of the entities, superclasses included. */
    static Set<Class<?>> idTypes(Set<Class<?>> entities) {
        var idTypes = sortedSet();
        for (var entity : entities) {
            ReflectionUtils.doWithFields(entity,
                    field -> idTypes.add(field.getType()),
                    field -> field.isAnnotationPresent(Id.class));
        }
        return idTypes;
    }

    /** The classes named in the entities' {@code @EntityListeners}. */
    static Set<Class<?>> entityListeners(Set<Class<?>> entities) {
        var listeners = sortedSet();
        for (var entity : entities) {
            var annotation = entity.getAnnotation(EntityListeners.class);
            if (annotation != null) {
                listeners.addAll(List.of(annotation.value()));
            }
        }
        return listeners;
    }

    /** The methods of a class (superclasses included) annotated with a JPA lifecycle callback. */
    static List<Method> lifecycleCallbacks(Class<?> type) {
        var callbacks = new ArrayList<Method>();
        ReflectionUtils.doWithMethods(type, callbacks::add,
                method -> LIFECYCLE_CALLBACKS.stream().anyMatch(method::isAnnotationPresent));
        return callbacks;
    }

    /**
     * The declared type of every JSON attribute (fields and record components of embeddables).
     * Plain JDK types such as {@code Map<String, String>} need no hints and are skipped.
     */
    static Set<Class<?>> jsonAttributeTypes(Set<Class<?>> entities) {
        var types = sortedSet();
        for (var entity : entities) {
            ReflectionUtils.doWithFields(entity,
                    field -> types.add(field.getType()),
                    HibernateRuntimeHints::isJsonAttribute);
        }
        types.removeIf(type -> type.isPrimitive() || type.getName().startsWith("java."));
        return types;
    }

    private static boolean isJsonAttribute(Field field) {
        var jdbcType = field.getAnnotation(JdbcTypeCode.class);
        return jdbcType != null && jdbcType.value() == SqlTypes.JSON;
    }

    private static Set<Class<?>> sortedSet() {
        return new TreeSet<>(Comparator.comparing(Class::getName));
    }
}
