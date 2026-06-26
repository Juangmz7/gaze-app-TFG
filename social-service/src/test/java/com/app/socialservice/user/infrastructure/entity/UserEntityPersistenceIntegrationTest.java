package com.app.socialservice.user.infrastructure.entity;

import com.app.socialservice.TestcontainersConfiguration;
import com.app.socialservice.user.domain.enums.UserAccountStatus;
import com.app.socialservice.user.infrastructure.repository.JpaUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class UserEntityPersistenceIntegrationTest {

    @Autowired
    private JpaUserRepository jpaUserRepository;

    @Autowired
    private JdbcClient jdbcClient;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() {
        jpaUserRepository.deleteAll();
    }

    @Test
    void shouldPersistUserBioSocialMediaAsJsonb() {
        var userId = UUID.randomUUID();

        jpaUserRepository.save(UserEntity.builder()
                .id(userId)
                .username("bio-user")
                .email("bio-user@example.com")
                .bio(UserBioEmbeddable.builder()
                        .description("Persisted bio")
                        .socialMedia(Map.of(
                                "github", "bio-user",
                                "linkedin", "bio-user-linkedin"
                        ))
                        .build())
                .accountStatus(UserAccountStatus.ACCEPTED)
                .build());

        assertThat(jpaUserRepository.findById(userId)).get()
                .extracting(UserEntity::getBio)
                .isNotNull();
        assertThat(jpaUserRepository.findById(userId)).get()
                .extracting(entity -> entity.getBio().getDescription())
                .isEqualTo("Persisted bio");
        assertThat(jpaUserRepository.findById(userId)).get()
                .extracting(entity -> entity.getBio().getSocialMedia())
                .isEqualTo(Map.of(
                        "github", "bio-user",
                        "linkedin", "bio-user-linkedin"
                ));

        var storedColumnType = jdbcClient.sql("""
                SELECT pg_typeof(social_media)::text
                FROM users
                WHERE id = :id
                """)
                .param("id", userId)
                .query(String.class)
                .single();

        assertThat(storedColumnType).isEqualTo("jsonb");
    }
}
