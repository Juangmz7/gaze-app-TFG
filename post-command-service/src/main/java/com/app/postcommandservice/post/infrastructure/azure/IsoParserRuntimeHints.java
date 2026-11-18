package com.app.postcommandservice.post.infrastructure.azure;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

/**
 * Native image hints for isoparser (mp4parser), which AzureMediaVerifier uses to read a
 * video's duration. It maps every MP4 box type to a class in isoparser2-default.properties
 * and instantiates it with Class.forName and its constructor, which a native image only
 * allows for registered classes. The mapping is read here, at AOT build time, so the
 * hints follow the library's own list instead of a copy of it.
 */
class IsoParserRuntimeHints implements RuntimeHintsRegistrar {

    static final String DEFAULT_MAPPING = "isoparser2-default.properties";
    static final String CUSTOM_MAPPING = "isoparser-custom.properties";

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.resources().registerPattern(DEFAULT_MAPPING);
        hints.resources().registerPattern(CUSTOM_MAPPING);

        for (var boxClass : boxClassNames(classLoader)) {
            hints.reflection().registerType(TypeReference.of(boxClass),
                    MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
        }
    }

    /** The class of every mapping value: "org.mp4parser...Box" or "org.mp4parser...Box(type)". */
    static java.util.Set<String> boxClassNames(ClassLoader classLoader) {
        var mapping = new Properties();
        try (InputStream stream = classLoader.getResourceAsStream(DEFAULT_MAPPING)) {
            if (stream == null) {
                throw new IllegalStateException(DEFAULT_MAPPING + " is not on the classpath");
            }
            mapping.load(stream);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }

        var classNames = new java.util.TreeSet<String>();
        for (var value : mapping.stringPropertyNames().stream().map(mapping::getProperty).toList()) {
            var className = value.contains("(") ? value.substring(0, value.indexOf('(')) : value;
            if (!className.isBlank()) {
                classNames.add(className.trim());
            }
        }
        return classNames;
    }
}
