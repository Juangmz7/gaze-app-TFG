package com.app.postcommandservice.shared.infrastructure.azure.config;

import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AzureStoragePropertiesTest {

    private ValidatorFactory validatorFactory;
    private Validator validator;

    @BeforeEach
    void setUp() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterEach
    void tearDown() {
        validatorFactory.close();
    }

    @Test
    void shouldFailValidationWhenAccountUrlOrContainerAreBlank() {
        AzureStorageProperties properties = new AzureStorageProperties();
        properties.setAccountUrl(" ");
        properties.setContainer("");

        Set<ConstraintViolation<AzureStorageProperties>> violations = validator.validate(properties);

        assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                .contains("accountUrl", "container");
    }

    @Test
    void shouldPassValidationWithRequiredFieldsSet() {
        AzureStorageProperties properties = new AzureStorageProperties();
        properties.setAccountUrl("https://myaccount.blob.core.windows.net");
        properties.setContainer("post-media");

        Set<ConstraintViolation<AzureStorageProperties>> violations = validator.validate(properties);

        assertThat(violations).isEmpty();
    }

    @Test
    void shouldDefaultUploadSasTtlToFifteenMinutes() {
        AzureStorageProperties properties = new AzureStorageProperties();

        assertThat(properties.getUploadSasTtl()).isEqualTo(java.time.Duration.ofMinutes(15));
    }
}
