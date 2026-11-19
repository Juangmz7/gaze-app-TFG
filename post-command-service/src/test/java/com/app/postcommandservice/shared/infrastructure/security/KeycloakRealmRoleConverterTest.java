package com.app.postcommandservice.shared.infrastructure.security;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakRealmRoleConverterTest {

    private final KeycloakRealmRoleConverter converter = new KeycloakRealmRoleConverter();

    @Test
    void shouldMapRealmRolesToRoleAuthorities() {
        var jwt = jwtWithClaim("realm_access", Map.of("roles", List.of("USER", "ADMIN")));

        assertThat(converter.convert(jwt))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
    }

    @Test
    void shouldIgnoreMissingOrMalformedRealmAccess() {
        assertThat(converter.convert(jwtWithClaim("other", "x"))).isEmpty();
        assertThat(converter.convert(jwtWithClaim("realm_access", "ADMIN"))).isEmpty();
        assertThat(converter.convert(jwtWithClaim("realm_access", Map.of("roles", "ADMIN")))).isEmpty();
    }

    @Test
    void shouldNotReadRolesFromCustomTopLevelClaim() {
        assertThat(converter.convert(jwtWithClaim("roles", List.of("ADMIN")))).isEmpty();
    }

    @Test
    void shouldSkipNonStringAndBlankRoles() {
        var jwt = jwtWithClaim("realm_access", Map.of("roles", List.of("ADMIN", " ", 42)));

        assertThat(converter.convert(jwt))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    void shouldCombineScopesAndRealmRolesInJwtAuthenticationConverter() {
        var jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("8f9a3a6e-7c2a-4a8e-9b51-3f3d8f3c2d10")
                .claim("scope", "profile")
                .claim("realm_access", Map.of("roles", List.of("ADMIN")))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        var authentication = new SecurityConfig(null, null).jwtAuthenticationConverter().convert(jwt);

        assertThat(authentication).isNotNull();
        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .contains("SCOPE_profile", "ROLE_ADMIN");
    }

    private static Jwt jwtWithClaim(String name, Object value) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("8f9a3a6e-7c2a-4a8e-9b51-3f3d8f3c2d10")
                .claim(name, value)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }
}
