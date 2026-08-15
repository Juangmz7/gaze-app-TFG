package com.app.postcommandservice.post.infrastructure.controller;

import org.junit.jupiter.api.Test;

import com.app.postcommandservice.view.domain.model.PostViewExitReason;
import com.app.postcommandservice.view.domain.model.PostViewSource;

import static org.assertj.core.api.Assertions.assertThat;

class ReportPostViewRequestTest {

    @Test
    void shouldConvertLowercaseBoundaryValuesToPureDomainEnums() {
        var context = new ReportPostViewRequest.ViewContextRequest("user_profile", 2);
        var playbackMetrics = new ReportPostViewRequest.PlaybackMetricsRequest(2400, 1200, 50, "navigated_away");

        assertThat(context.toSource()).isEqualTo(PostViewSource.USER_PROFILE);
        assertThat(playbackMetrics.toExitReason()).isEqualTo(PostViewExitReason.NAVIGATED_AWAY);
    }
}
