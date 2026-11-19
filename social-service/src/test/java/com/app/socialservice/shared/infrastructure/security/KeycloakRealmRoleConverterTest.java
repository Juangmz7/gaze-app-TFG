package com.app.socialservice.shared.infrastructure.security;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakRealmRoleConverterTest {

    private final KeycloakRealmRoleConverter converter = new KeycloakRealmRoleConverter();

    @Test
    void shouldMapRealmRolesToRolePrefixedAuthorities() {
        var jwt = jwtWithClaim(KeycloakRealmRoleConverter.REALM_ACCESS_CLAIM,
                Map.of(KeycloakRealmRoleConverter.ROLES_KEY, List.of("ADMIN", "USER", "ADMIN")));

        assertThat(converter.convert(jwt)).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN", "ROLE_USER");
    }

    @Test
    void shouldGiveNoAuthoritiesWithoutRealmAccess() {
        assertThat(converter.convert(jwtWithClaim("scope", "openid"))).isEmpty();
        assertThat(converter.convert(null)).isEmpty();
    }

    @Test
    void shouldIgnoreAMalformedClaimAndNonTextRoles() {
        assertThat(converter.convert(jwtWithClaim(KeycloakRealmRoleConverter.REALM_ACCESS_CLAIM, "ADMIN"))).isEmpty();
        assertThat(converter.convert(jwtWithClaim(KeycloakRealmRoleConverter.REALM_ACCESS_CLAIM,
                Map.of(KeycloakRealmRoleConverter.ROLES_KEY, "ADMIN")))).isEmpty();
        var mixed = jwtWithClaim(KeycloakRealmRoleConverter.REALM_ACCESS_CLAIM,
                Map.of(KeycloakRealmRoleConverter.ROLES_KEY, Arrays.asList(42, " ", null, "USER")));

        assertThat(converter.convert(mixed)).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_USER");
    }

    @Test
    void shouldNotReadRolesFromAClientOrCustomClaim() {
        var jwt = jwtWithClaim("resource_access",
                Map.of("mobile-app", Map.of(KeycloakRealmRoleConverter.ROLES_KEY, List.of("ADMIN"))));

        assertThat(converter.convert(jwt)).isEmpty();
    }

    private static Jwt jwtWithClaim(String name, Object value) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("user")
                .issuedAt(Instant.now())
                .claim(name, value)
                .build();
    }
}
