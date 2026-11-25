package com.app.postcommandservice.shared.infrastructure.validation;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

/** Bean Validation is auto-configured by Spring Boot; this only adds its native image hints. */
@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(ValidationRuntimeHints.class)
class ValidationConfig {
}
