package com.app.postcommandservice.post.application.dto;

import com.app.postcommandservice.collab.application.dto.CollabResponse;

public record CheckPostCollabLinkStatusResult(
        boolean linked,
        CollabResponse collab
) {

    public static CheckPostCollabLinkStatusResult unlinked() {
        return new CheckPostCollabLinkStatusResult(false, null);
    }

    public static CheckPostCollabLinkStatusResult linked(CollabResponse collab) {
        return new CheckPostCollabLinkStatusResult(true, collab);
    }
}
