package com.app.postcommandservice.post.infrastructure.entity;

import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

public record UserBioPayload(
        String description,
        @JdbcTypeCode(SqlTypes.JSON)
        Map<String, String> socialMedia
) {
}
