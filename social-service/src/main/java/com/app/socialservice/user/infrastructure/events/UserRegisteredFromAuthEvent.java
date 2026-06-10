package com.app.socialservice.user.infrastructure.events;

public record UserRegisteredFromAuthEvent(
        Long time,
        String type,
        String realmId,
        String clientId,
        String userId,
        String sessionId,
        String ipAddress,
        String error,
        Details details
) {

    public record Details(
            String auth_method,
            String auth_type,
            String register_method,
            String redirect_uri,
            String first_name,
            String last_name,
            String email,
            String username
    ) {
    }
}