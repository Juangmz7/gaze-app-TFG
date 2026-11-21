package com.app.socialservice.follow.infrastructure.controller;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.app.socialservice.TestcontainersConfiguration;
import com.app.socialservice.block.infrastructure.repository.JpaBlockRepository;
import com.app.socialservice.follow.infrastructure.enums.FollowStatus;
import com.app.socialservice.follow.infrastructure.repository.JpaFollowRepository;
import com.app.socialservice.follow.testutil.FollowMother;
import com.app.socialservice.shared.domain.pagination.KeysetCursor;
import com.app.socialservice.shared.domain.pagination.PageLimit;
import com.app.socialservice.shared.testutil.SocialIntegrationSeeder;
import com.app.socialservice.user.domain.enums.UserAccountStatus;
import com.app.socialservice.user.infrastructure.repository.JpaUserRepository;
import com.app.socialservice.user.testutil.UserMother;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class FollowListControllerIntegrationTest {

    private static final String FOLLOWERS_PATH = "/api/social/follow/%s/followers";
    private static final String FOLLOWING_PATH = "/api/social/follow/%s/following";
    private static final String NOT_FOUND_ERROR_CODE = "NOT_FOUND";
    private static final String BAD_REQUEST_ERROR_CODE = "BAD_REQUEST";
    private static final String INVALID_CURSOR_ERROR_CODE = "invalid_cursor";
    private static final String INVALID_LIMIT_ERROR_CODE = "invalid_limit";
    private static final Instant NEWEST_FOLLOW = Instant.parse("2026-11-19T10:00:00.123456Z");
    // Listed user id DESC as PostgreSQL orders uuid: unsigned bytes, the same as the lowercase text
    private static final Comparator<Seeded> LIST_ORDER = Comparator.comparing(Seeded::followedAt)
            .thenComparing(seeded -> seeded.userId().toString())
            .reversed();

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JpaUserRepository jpaUserRepository;

    @Autowired
    private JpaFollowRepository jpaFollowRepository;

    @Autowired
    private JpaBlockRepository jpaBlockRepository;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private record Seeded(UUID userId, Instant followedAt) {
    }

    @BeforeEach
    void setUp() {
        jpaFollowRepository.deleteAll();
        jpaBlockRepository.deleteAll();
        jpaUserRepository.deleteAll();
    }

    @Test
    void followersOf45UsersComeBackInThreePagesNewestFirstWithoutDuplicates() throws Exception {
        var callerId = seedUser("caller");
        var ownerId = seedUser("owner");
        var followers = new ArrayList<Seeded>();
        for (int i = 0; i < 45; i++) {
            var followerId = seedUser("follower" + i);
            // Three follows share each instant, so the user id breaks the ties
            var followedAt = NEWEST_FOLLOW.minusSeconds(i / 3);
            seedFollow(followerId, ownerId, FollowStatus.ACTIVE, followedAt);
            followers.add(new Seeded(followerId, followedAt));
        }
        var expectedOrder = followers.stream().sorted(LIST_ORDER).map(Seeded::userId).toList();

        var first = getPage(FOLLOWERS_PATH, ownerId, callerId, null);
        var second = getPage(FOLLOWERS_PATH, ownerId, callerId, first.get("nextCursor").asString());
        var third = getPage(FOLLOWERS_PATH, ownerId, callerId, second.get("nextCursor").asString());

        assertThat(first.get("items")).hasSize(PageLimit.DEFAULT);
        assertThat(second.get("items")).hasSize(PageLimit.DEFAULT);
        assertThat(third.get("items")).hasSize(5);
        assertThat(third.has("nextCursor")).isTrue();
        assertThat(third.get("nextCursor").isNull()).isTrue();
        var listed = new ArrayList<UUID>();
        listed.addAll(userIdsOf(first));
        listed.addAll(userIdsOf(second));
        listed.addAll(userIdsOf(third));
        assertThat(listed).containsExactlyElementsOf(expectedOrder);
        assertThat(new HashSet<>(listed)).hasSize(45);
        assertThat(KeysetCursor.decode(first.get("nextCursor").asString()))
                .isEqualTo(new KeysetCursor(followedAtOf(followers, expectedOrder.get(19)), expectedOrder.get(19)));
    }

    @Test
    void followingIsTrueExactlyForTheUsersTheCallerFollows() throws Exception {
        var callerId = seedUser("caller");
        var ownerId = seedUser("owner");
        var followedByCaller = new HashSet<UUID>();
        var followers = new ArrayList<UUID>();
        for (int i = 0; i < 10; i++) {
            var followerId = seedUser("follower" + i);
            seedFollow(followerId, ownerId, FollowStatus.ACTIVE, NEWEST_FOLLOW.minusSeconds(i));
            followers.add(followerId);
            if (i % 3 == 0) {
                seedFollow(callerId, followerId, FollowStatus.ACTIVE, NEWEST_FOLLOW);
                followedByCaller.add(followerId);
            }
        }
        // A follow the caller removed and one of the listed users following the caller do not count
        seedFollow(callerId, followers.get(1), FollowStatus.REMOVED, NEWEST_FOLLOW);
        seedFollow(followers.get(2), callerId, FollowStatus.ACTIVE, NEWEST_FOLLOW);

        var page = getPage(FOLLOWERS_PATH, ownerId, callerId, null);

        var flagged = new HashSet<UUID>();
        page.get("items").forEach(item -> {
            if (item.get("following").asBoolean()) {
                flagged.add(UUID.fromString(item.get("userId").asString()));
            }
        });
        assertThat(flagged).isEqualTo(followedByCaller);
    }

    @Test
    void followersItemsHaveExactlyTheContractFields() throws Exception {
        var callerId = seedUser("caller");
        var ownerId = seedUser("owner");
        var followerId = UUID.randomUUID();
        SocialIntegrationSeeder.seedUser(jpaUserRepository, UserMother.entity(followerId, "with.picture",
                UserAccountStatus.ACCEPTED, Instant.now(), null, null, "https://cdn.example.com/a.jpg"));
        var noPictureId = seedUser("no.picture");
        seedFollow(followerId, ownerId, FollowStatus.ACTIVE, NEWEST_FOLLOW);
        seedFollow(noPictureId, ownerId, FollowStatus.ACTIVE, NEWEST_FOLLOW.minusSeconds(1));

        var page = getPage(FOLLOWERS_PATH, ownerId, callerId, null);

        assertThat(page.propertyNames()).containsExactlyInAnyOrder("items", "nextCursor");
        var withPicture = page.get("items").get(0);
        assertThat(withPicture.propertyNames())
                .containsExactlyInAnyOrder("userId", "username", "profilePic", "following");
        assertThat(withPicture.get("userId").asString()).isEqualTo(followerId.toString());
        assertThat(withPicture.get("username").asString()).isEqualTo("with.picture");
        assertThat(withPicture.get("profilePic").asString()).isEqualTo("https://cdn.example.com/a.jpg");
        assertThat(withPicture.get("following").asBoolean()).isFalse();
        var withoutPicture = page.get("items").get(1);
        assertThat(withoutPicture.has("profilePic")).isTrue();
        assertThat(withoutPicture.get("profilePic").isNull()).isTrue();
    }

    @Test
    void followingListPagesWithTheRequestedLimitNewestFirst() throws Exception {
        var callerId = seedUser("caller");
        var ownerId = seedUser("owner");
        var followed = new ArrayList<Seeded>();
        for (int i = 0; i < 7; i++) {
            var followedId = seedUser("followed" + i);
            var followedAt = NEWEST_FOLLOW.minusMillis(i / 2);
            seedFollow(ownerId, followedId, FollowStatus.ACTIVE, followedAt);
            followed.add(new Seeded(followedId, followedAt));
        }
        // Follows of other users are not part of the owner's list
        seedFollow(callerId, followed.get(0).userId(), FollowStatus.ACTIVE, NEWEST_FOLLOW);
        var expectedOrder = followed.stream().sorted(LIST_ORDER).map(Seeded::userId).toList();

        var listed = new ArrayList<UUID>();
        String cursor = null;
        int pages = 0;
        do {
            var page = getPage(FOLLOWING_PATH + "?limit=3", ownerId, callerId, cursor);
            assertThat(page.get("items").size()).isLessThanOrEqualTo(3);
            listed.addAll(userIdsOf(page));
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asString();
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);
        assertThat(listed).containsExactlyElementsOf(expectedOrder);
    }

    @Test
    void listsLeaveOutUsersInABlockRelationWithTheCallerHiddenAccountsAndInactiveFollows() throws Exception {
        var callerId = seedUser("caller");
        var ownerId = seedUser("owner");
        var visibleId = seedUser("visible");
        var blockedByCallerId = seedUser("blocked.by.caller");
        var blockerOfCallerId = seedUser("blocker.of.caller");
        var bannedId = seedUser("banned", UserAccountStatus.BANNED);
        var deletedId = seedUser("deleted", UserAccountStatus.DELETED);
        var removedId = seedUser("removed");
        var blockedFollowId = seedUser("blocked.follow");
        for (var userId : List.of(visibleId, blockedByCallerId, blockerOfCallerId, bannedId, deletedId)) {
            seedFollow(userId, ownerId, FollowStatus.ACTIVE, NEWEST_FOLLOW);
            seedFollow(ownerId, userId, FollowStatus.ACTIVE, NEWEST_FOLLOW);
        }
        seedFollow(removedId, ownerId, FollowStatus.REMOVED, NEWEST_FOLLOW);
        seedFollow(ownerId, removedId, FollowStatus.REMOVED, NEWEST_FOLLOW);
        seedFollow(blockedFollowId, ownerId, FollowStatus.BLOCKED, NEWEST_FOLLOW);
        seedFollow(ownerId, blockedFollowId, FollowStatus.BLOCKED, NEWEST_FOLLOW);
        seedBlock(callerId, blockedByCallerId);
        seedBlock(blockerOfCallerId, callerId);

        var followers = getPage(FOLLOWERS_PATH, ownerId, callerId, null);
        var following = getPage(FOLLOWING_PATH, ownerId, callerId, null);

        assertThat(userIdsOf(followers)).containsExactly(visibleId);
        assertThat(userIdsOf(following)).containsExactly(visibleId);
    }

    @Test
    void callerCanListTheirOwnFollowersAndFollowing() throws Exception {
        var callerId = seedUser("caller");
        var followerId = seedUser("follower");
        seedFollow(followerId, callerId, FollowStatus.ACTIVE, NEWEST_FOLLOW);
        seedFollow(callerId, followerId, FollowStatus.ACTIVE, NEWEST_FOLLOW);

        var followers = getPage(FOLLOWERS_PATH, callerId, callerId, null);
        var following = getPage(FOLLOWING_PATH, callerId, callerId, null);

        assertThat(userIdsOf(followers)).containsExactly(followerId);
        assertThat(followers.get("items").get(0).get("following").asBoolean()).isTrue();
        assertThat(userIdsOf(following)).containsExactly(followerId);
    }

    @Test
    void unknownBlockedOrHiddenListOwnersAnswerNotFoundLikeTheProfile() throws Exception {
        var callerId = seedUser("caller");
        var blockedByCallerId = seedUser("blocked.by.caller");
        var blockerOfCallerId = seedUser("blocker.of.caller");
        var bannedId = seedUser("banned", UserAccountStatus.BANNED);
        var deletedId = seedUser("deleted", UserAccountStatus.DELETED);
        seedBlock(callerId, blockedByCallerId);
        seedBlock(blockerOfCallerId, callerId);

        for (var ownerId : List.of(UUID.randomUUID(), blockedByCallerId, blockerOfCallerId, bannedId, deletedId)) {
            for (var path : List.of(FOLLOWERS_PATH, FOLLOWING_PATH)) {
                perform(path, ownerId.toString(), callerId, null)
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.errorCode").value(NOT_FOUND_ERROR_CODE));
            }
        }
    }

    @Test
    void malformedUserIdAnswersBadRequest() throws Exception {
        var callerId = seedUser("caller");

        for (var path : List.of(FOLLOWERS_PATH, FOLLOWING_PATH)) {
            perform(path, "not-a-uuid", callerId, null)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value(BAD_REQUEST_ERROR_CODE));
        }
    }

    @Test
    void malformedCursorAnswersInvalidCursor() throws Exception {
        var callerId = seedUser("caller");
        var ownerId = seedUser("owner");

        for (var cursor : List.of("garbage!", "", "MTIzNDU2Nzg5MC4wMDAwMDAwMDA6eHl6")) {
            for (var path : List.of(FOLLOWERS_PATH, FOLLOWING_PATH)) {
                perform(path, ownerId.toString(), callerId, cursor)
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.errorCode").value(INVALID_CURSOR_ERROR_CODE));
            }
        }
    }

    @Test
    void badLimitAnswersInvalidLimit() throws Exception {
        var callerId = seedUser("caller");
        var ownerId = seedUser("owner");

        for (var limit : List.of("0", "51", "-1", "abc", "2.5", "")) {
            for (var path : List.of(FOLLOWERS_PATH, FOLLOWING_PATH)) {
                mockMvc.perform(get(path.formatted(ownerId)).param("limit", limit)
                                .with(jwt().jwt(token -> token.subject(callerId.toString()))))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.errorCode").value(INVALID_LIMIT_ERROR_CODE));
            }
        }
    }

    private JsonNode getPage(String pathTemplate, UUID ownerId, UUID callerId, String cursor) throws Exception {
        var content = perform(pathTemplate, ownerId.toString(), callerId, cursor)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return jsonMapper.readTree(content);
    }

    private ResultActions perform(String pathTemplate, String ownerId, UUID callerId, String cursor) throws Exception {
        var request = get(pathTemplate.formatted(ownerId))
                .with(jwt().jwt(token -> token.subject(callerId.toString())));
        if (cursor != null) {
            request.param("cursor", cursor);
        }
        return mockMvc.perform(request);
    }

    private static List<UUID> userIdsOf(JsonNode page) {
        var userIds = new ArrayList<UUID>();
        page.get("items").forEach(item -> userIds.add(UUID.fromString(item.get("userId").asString())));
        return userIds;
    }

    private static Instant followedAtOf(List<Seeded> seeded, UUID userId) {
        return seeded.stream()
                .filter(entry -> entry.userId().equals(userId))
                .findFirst()
                .map(Seeded::followedAt)
                .orElseThrow();
    }

    private UUID seedUser(String username) {
        return seedUser(username, UserAccountStatus.ACCEPTED);
    }

    private UUID seedUser(String username, UserAccountStatus accountStatus) {
        var userId = UUID.randomUUID();
        SocialIntegrationSeeder.seedUser(jpaUserRepository,
                UserMother.entity(userId, username, accountStatus, Instant.now(), null, Map.of(), null));
        return userId;
    }

    private void seedFollow(UUID followerId, UUID followedId, FollowStatus status, Instant createdAt) {
        SocialIntegrationSeeder.seedFollow(jpaFollowRepository,
                FollowMother.entity(followerId, followedId, status, createdAt, createdAt));
    }

    private void seedBlock(UUID blockerId, UUID blockedId) {
        SocialIntegrationSeeder.seedBlock(jpaBlockRepository, blockerId, blockedId, NEWEST_FOLLOW);
    }
}
