package com.app.socialservice.shared.infrastructure.security;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ApiErrorResponse {
    private Instant timestamp;

    private Integer status;

    private String error;

    private ApiErrorCode errorCode;

    private String message;

    private String path;
}
