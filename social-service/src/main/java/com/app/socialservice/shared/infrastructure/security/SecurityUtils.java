package com.app.socialservice.shared.infrastructure.security;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class SecurityUtils {

    public Jwt getCurrentJwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication != null && authentication.getPrincipal() instanceof Jwt) {
            return (Jwt) authentication.getPrincipal();
        }
        return null;
    }

    public UUID getUserId() {
        var userIdClaim = getClaim("sub");
        if (userIdClaim == null) {
            return null;
        }
        try {
            return UUID.fromString(userIdClaim);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public String getClaim(String claimName) {
        Jwt jwt = getCurrentJwt();
        return (jwt != null) ? jwt.getClaimAsString(claimName) : null;
    }
}
