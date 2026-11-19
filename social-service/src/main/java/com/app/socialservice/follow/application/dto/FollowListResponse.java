package com.app.socialservice.follow.application.dto;

import java.util.List;

public record FollowListResponse(
        List<FollowListItemResponse> items,
        String nextCursor
) {
}
