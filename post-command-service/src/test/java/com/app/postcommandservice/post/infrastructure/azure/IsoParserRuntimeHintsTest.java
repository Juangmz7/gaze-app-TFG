package com.app.postcommandservice.post.infrastructure.azure;

import org.junit.jupiter.api.Test;
import org.mp4parser.boxes.iso14496.part12.MovieBox;
import org.mp4parser.boxes.iso14496.part12.MovieHeaderBox;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

import static org.assertj.core.api.Assertions.assertThat;

class IsoParserRuntimeHintsTest {

    private final RuntimeHints hints = new RuntimeHints();

    IsoParserRuntimeHintsTest() {
        new IsoParserRuntimeHints().registerHints(hints, getClass().getClassLoader());
    }

    @Test
    void shouldIncludeTheBoxMappingIsoparserLoadsAtRuntime() {
        assertThat(RuntimeHintsPredicates.resource().forResource(IsoParserRuntimeHints.DEFAULT_MAPPING)).accepts(hints);
    }

    @Test
    void shouldLetIsoparserInstantiateTheBoxesTheMediaVerifierReads() throws NoSuchMethodException {
        // isoparser builds them with Class.forName and their constructor
        assertThat(RuntimeHintsPredicates.reflection().onConstructorInvocation(MovieBox.class.getConstructor()))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onConstructorInvocation(MovieHeaderBox.class.getConstructor()))
                .accepts(hints);
    }

    @Test
    void shouldRegisterEveryMappedBoxClassWithoutItsConstructorArguments() {
        var classNames = IsoParserRuntimeHints.boxClassNames(getClass().getClassLoader());

        assertThat(classNames).hasSizeGreaterThan(100)
                .contains(MovieBox.class.getName(), MovieHeaderBox.class.getName())
                .allSatisfy(name -> assertThat(name).doesNotContain("(").doesNotContain(" "));
    }
}
