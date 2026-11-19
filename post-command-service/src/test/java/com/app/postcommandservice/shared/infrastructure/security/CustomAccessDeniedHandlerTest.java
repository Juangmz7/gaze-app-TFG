package com.app.postcommandservice.shared.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class CustomAccessDeniedHandlerTest {

    @Test
    void shouldWriteForbiddenApiErrorBody() throws Exception {
        var handler = new CustomAccessDeniedHandler(JsonMapper.builder().build());
        var request = new MockHttpServletRequest("GET", "/api/posts/reports");
        request.setRequestURI("/api/posts/reports");
        var response = new MockHttpServletResponse();

        handler.handle(request, response, new AccessDeniedException("Access Denied"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString())
                .contains("\"errorCode\":\"FORBIDDEN\"")
                .contains("\"path\":\"/api/posts/reports\"");
    }
}
