package com.app.postcommandservice.post.application.dto;

import com.app.postcommandservice.collab.application.dto.CollabResponse;

public record PostCollabLinkStatusResponse(
        boolean linked,
        CollabResponse collab
) {
}
