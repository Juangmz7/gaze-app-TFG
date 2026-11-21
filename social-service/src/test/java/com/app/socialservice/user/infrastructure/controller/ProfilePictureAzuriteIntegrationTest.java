package com.app.socialservice.user.infrastructure.controller;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobContainerClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.app.socialservice.TestcontainersConfiguration;
import com.app.socialservice.block.infrastructure.repository.JpaBlockRepository;
import com.app.socialservice.follow.infrastructure.repository.JpaFollowRepository;
import com.app.socialservice.follow.testutil.FollowMother;
import com.app.socialservice.report.infrastructure.repository.JpaUserReportRepository;
import com.app.socialservice.shared.infrastructure.azure.BlobContainerLocation;
import com.app.socialservice.shared.testutil.SocialIntegrationSeeder;
import com.app.socialservice.user.application.cache.CacheNames;
import com.app.socialservice.user.application.dto.OwnUserProfileResponse;
import com.app.socialservice.user.domain.enums.UserAccountStatus;
import com.app.socialservice.user.infrastructure.entity.UserBioEmbeddable;
import com.app.socialservice.user.infrastructure.entity.UserEntity;
import com.app.socialservice.user.infrastructure.repository.JpaUserRepository;
import com.app.socialservice.user.infrastructure.repository.UserNodeRepository;

import static com.app.socialservice.shared.testutil.AzureStorageTestConstants.BLOB_RESOURCE;
import static com.app.socialservice.shared.testutil.AzureStorageTestConstants.READ_PERMISSION;
import static com.app.socialservice.shared.testutil.AzureStorageTestConstants.UPLOAD_PERMISSION;
import static com.app.socialservice.shared.testutil.AzureStorageTestConstants.queryParameters;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Avatar upload and read-signed profile pictures against a real Azurite
 * (TestcontainersConfiguration), with the whole application context.
 */
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ProfilePictureAzuriteIntegrationTest {

    private static final String UPLOAD_URL_PATH = "/api/social/profile/me/picture/upload-url";
    private static final String OWN_PROFILE_PATH = "/api/social/profile/me";
    private static final byte[] JPEG_BYTES = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x10, 0x4A, 0x46};
    private static final String PICTURE = "7c9e6679-7425-40de-944b-e07fc1f90ae7";

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BlobContainerClient blobContainerClient;

    @Autowired
    private BlobContainerLocation blobContainerLocation;

    @Autowired
    private JpaUserRepository jpaUserRepository;

    @Autowired
    private JpaFollowRepository jpaFollowRepository;

    @Autowired
    private JpaBlockRepository jpaBlockRepository;

    @Autowired
    private UserNodeRepository userNodeRepository;

    @Autowired
    private JpaUserReportRepository jpaUserReportRepository;

    @Autowired
    private Neo4jClient neo4jClient;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() {
        blobContainerClient.createIfNotExists();
        jpaBlockRepository.deleteAll();
        jpaFollowRepository.deleteAll();
        jpaUserRepository.deleteAll();
        neo4jClient.query("MATCH (n) DETACH DELETE n").run();
        var connection = stringRedisTemplate.getConnectionFactory().getConnection();
        try {
            connection.serverCommands().flushDb();
        } finally {
            connection.close();
        }
    }

    @Test
    void uploadUrlReturnsAWriteOnlySasThatAcceptsAPutOnAzurite() throws Exception {
        var userId = seedUser("uploader", null);

        var body = requestUploadUrl(userId, "image/jpeg", JPEG_BYTES.length);

        var uploadUrl = body.get("uploadUrl").asString();
        var pictureUrl = body.get("pictureUrl").asString();
        assertThat(pictureUrl).doesNotContain("?");
        assertThat(blobContainerLocation.blobNameOfBareUrl(pictureUrl))
                .hasValueSatisfying(name -> assertThat(name).matches("avatars/" + userId + "/[0-9a-f-]{36}\\.jpg"));
        assertThat(uploadUrl).startsWith(pictureUrl + "?");
        assertThat(queryParameters(uploadUrl))
                .containsEntry("sp", UPLOAD_PERMISSION)
                .containsEntry("sr", BLOB_RESOURCE);
        assertThat(Instant.parse(body.get("expiresAt").asString()))
                .isBetween(Instant.now().plusSeconds(14 * 60), Instant.now().plusSeconds(15 * 60 + 5));

        var upload = putBlob(uploadUrl, JPEG_BYTES);
        var readBack = httpClient.send(HttpRequest.newBuilder(URI.create(uploadUrl)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertThat(upload.statusCode()).isEqualTo(201);
        assertThat(blobContainerClient.getBlobClient(blobContainerLocation.blobNameOfBareUrl(pictureUrl).orElseThrow())
                .downloadContent().toBytes()).isEqualTo(JPEG_BYTES);
        assertThat(readBack.statusCode()).isEqualTo(403);
    }

    @Test
    void ownProfileReturnsAReadSignedProfilePictureThatDownloadsAndNeverStoresTheSas() throws Exception {
        var userId = seedUser("avatar-owner", "https://cdn.example.com/old.png");
        // The app reads its own profile at start-up, so a cached profile exists before the update
        mockMvc.perform(authenticated(get(OWN_PROFILE_PATH), userId))
                .andExpect(jsonPath("$.profilePicture").value("https://cdn.example.com/old.png"));
        var body = requestUploadUrl(userId, "image/jpeg", JPEG_BYTES.length);
        var pictureUrl = body.get("pictureUrl").asString();
        assertThat(putBlob(body.get("uploadUrl").asString(), JPEG_BYTES).statusCode()).isEqualTo(201);

        mockMvc.perform(authenticated(put(OWN_PROFILE_PATH), userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("profilePicture", pictureUrl))))
                .andExpect(status().isOk());
        var profile = readJson(mockMvc.perform(authenticated(get(OWN_PROFILE_PATH), userId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        var signedPicture = profile.get("profilePicture").asString();
        assertThat(signedPicture).startsWith(pictureUrl + "?");
        assertThat(queryParameters(signedPicture))
                .containsEntry("sp", READ_PERMISSION)
                .containsEntry("sr", BLOB_RESOURCE);
        var download = httpClient.send(HttpRequest.newBuilder(URI.create(signedPicture)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).isEqualTo(JPEG_BYTES);

        assertThat(jpaUserRepository.findById(userId).orElseThrow().getPictureUrl()).isEqualTo(pictureUrl);
        var cached = cacheManager.getCache(CacheNames.OWN_PROFILE).get(userId.toString(), OwnUserProfileResponse.class);
        assertThat(cached).isNotNull();
        assertThat(cached.profilePicture()).isEqualTo(pictureUrl);
    }

    @Test
    void sendingBackTheSignedPictureKeepsTheBareUrlStored() throws Exception {
        var userId = UUID.randomUUID();
        var pictureUrl = uploadAvatarOf(userId);
        seedUser(userId, "echo-user", pictureUrl);
        var signedPicture = readJson(mockMvc.perform(authenticated(get(OWN_PROFILE_PATH), userId))
                .andReturn().getResponse().getContentAsString()).get("profilePicture").asString();

        mockMvc.perform(authenticated(put(OWN_PROFILE_PATH), userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "profilePicture", signedPicture,
                                "description", "A new bio"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("A new bio"));

        assertThat(jpaUserRepository.findById(userId).orElseThrow().getPictureUrl()).isEqualTo(pictureUrl);
    }

    @Test
    void pictureUnderAnotherUsersAvatarsFolderIsRejected() throws Exception {
        var userId = seedUser("thief", null);
        var otherUsersPicture = uploadAvatarOf(UUID.randomUUID());

        mockMvc.perform(authenticated(put(OWN_PROFILE_PATH), userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("profilePicture", otherUsersPicture))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("invalid_profile_picture"));

        assertThat(jpaUserRepository.findById(userId).orElseThrow().getPictureUrl()).isNull();
    }

    @Test
    void pictureThatWasNeverUploadedIsRejected() throws Exception {
        var userId = seedUser("impatient", null);
        var notUploaded = blobContainerLocation.blobUrl("avatars/" + userId + "/" + PICTURE + ".png");

        mockMvc.perform(authenticated(put("/api/social/profile"), userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("profilePicture", notUploaded))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("profile_picture_not_uploaded"));
    }

    @Test
    void uploadUrlRejectsUnsupportedContentTypesAndSizes() throws Exception {
        var userId = seedUser("picky", null);

        mockMvc.perform(authenticated(post(UPLOAD_URL_PATH), userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\":\"image/gif\",\"sizeBytes\":100}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("unsupported_content_type"));
        mockMvc.perform(authenticated(post(UPLOAD_URL_PATH), userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\":\"image/png\",\"sizeBytes\":5242881}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("invalid_size"));
        mockMvc.perform(authenticated(post(UPLOAD_URL_PATH), userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\":\"image/png\",\"sizeBytes\":12.5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("invalid_size"));
        mockMvc.perform(authenticated(post(UPLOAD_URL_PATH), userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\":\"image/webp\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("invalid_size"));
    }

    @Test
    void publicProfileBlockedUsersAndRecommendedUsersReturnReadSignedPictures() throws Exception {
        var requesterId = seedUser("requester", null);
        var mutualId = seedUser("mutual", null);
        var candidateId = UUID.randomUUID();
        var candidatePicture = uploadAvatarOf(candidateId);
        seedUser(candidateId, "candidate", candidatePicture);
        var blockedId = UUID.randomUUID();
        var blockedPicture = uploadAvatarOf(blockedId);
        seedUser(blockedId, "blocked", blockedPicture);
        SocialIntegrationSeeder.seedBlock(jpaBlockRepository, requesterId, blockedId, Instant.now());
        for (var id : new UUID[] {requesterId, mutualId, candidateId}) {
            SocialIntegrationSeeder.seedUserNode(userNodeRepository, id);
        }
        SocialIntegrationSeeder.seedGraphFollow(neo4jClient, requesterId, mutualId);
        SocialIntegrationSeeder.seedGraphFollow(neo4jClient, mutualId, candidateId);

        var profile = getJson("/api/social/profile/" + candidateId, requesterId);
        var blocked = getJson("/api/social/block/users", requesterId);
        var recommended = getJson("/api/social/recommended/users", requesterId);

        assertDownloadableSignedUrlOf(profile.get("profilePic").asString(), candidatePicture);
        assertDownloadableSignedUrlOf(blocked.get(0).get("profilePic").asString(), blockedPicture);
        assertDownloadableSignedUrlOf(recommended.get(0).get("profilePic").asString(), candidatePicture);
    }

    @Test
    void profileByUsernameReturnsTheSameReadSignedPictureAsTheProfileById() throws Exception {
        var requesterId = seedUser("requester", null);
        var targetId = UUID.randomUUID();
        var targetPicture = uploadAvatarOf(targetId);
        seedUser(targetId, "mentioned.user", targetPicture);

        var byId = getJson("/api/social/profile/" + targetId, requesterId);
        var byUsername = getJson("/api/social/profile/by-username/Mentioned.User", requesterId);

        assertThat(byUsername).isEqualTo(byId);
        assertDownloadableSignedUrlOf(byUsername.get("profilePic").asString(), targetPicture);
    }

    @Test
    void followersAndFollowingListsReturnReadSignedPictures() throws Exception {
        var requesterId = seedUser("requester", null);
        var ownerId = seedUser("owner", null);
        var followerId = UUID.randomUUID();
        var followerPicture = uploadAvatarOf(followerId);
        seedUser(followerId, "follower", followerPicture);
        var followedId = UUID.randomUUID();
        var followedPicture = uploadAvatarOf(followedId);
        seedUser(followedId, "followed", followedPicture);
        SocialIntegrationSeeder.seedFollow(jpaFollowRepository,
                FollowMother.activeEntity(followerId, ownerId, Instant.now(), Instant.now()));
        SocialIntegrationSeeder.seedFollow(jpaFollowRepository,
                FollowMother.activeEntity(ownerId, followedId, Instant.now(), Instant.now()));

        var followers = getJson("/api/social/follow/" + ownerId + "/followers", requesterId);
        var following = getJson("/api/social/follow/" + ownerId + "/following", requesterId);

        assertDownloadableSignedUrlOf(followers.get("items").get(0).get("profilePic").asString(), followerPicture);
        assertDownloadableSignedUrlOf(following.get("items").get(0).get("profilePic").asString(), followedPicture);
        assertThat(jpaUserRepository.findById(followerId).orElseThrow().getPictureUrl()).isEqualTo(followerPicture);
    }

    @Test
    void theAdminListOfUserReportsReturnsTheReadSignedCurrentPictureAndTheBareSnapshot() throws Exception {
        jpaUserReportRepository.deleteAll();
        var reporterId = seedUser("reporter", null);
        var adminId = seedUser("admin", null);
        var reportedId = UUID.randomUUID();
        var picture = uploadAvatarOf(reportedId);
        seedUser(reportedId, "reported", picture);
        mockMvc.perform(authenticated(post("/api/social/reports/users"), reporterId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "reportedUserId", reportedId.toString(),
                                "reason", "INAPPROPRIATE_PROFILE"))))
                .andExpect(status().isCreated());

        var page = readJson(mockMvc.perform(get("/api/social/reports/users")
                        .with(jwt().jwt(token -> token.subject(adminId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        var item = page.get("items").get(0);
        assertDownloadableSignedUrlOf(item.get("reportedUser").get("profilePic").asString(), picture);
        assertThat(item.get("snapshot").get("profilePic").asString()).isEqualTo(picture);
        assertThat(jpaUserReportRepository.findAll().getFirst().getSnapshot()).doesNotContain("?");
    }

    private void assertDownloadableSignedUrlOf(String signedUrl, String bareUrl) throws Exception {
        assertThat(signedUrl).startsWith(bareUrl + "?");
        assertThat(queryParameters(signedUrl)).containsEntry("sp", READ_PERMISSION);
        var download = httpClient.send(HttpRequest.newBuilder(URI.create(signedUrl)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).isEqualTo(JPEG_BYTES);
    }

    private String uploadAvatarOf(UUID ownerId) {
        var blobName = "avatars/" + ownerId + "/" + UUID.randomUUID() + ".jpg";
        blobContainerClient.getBlobClient(blobName).upload(BinaryData.fromBytes(JPEG_BYTES), true);
        return blobContainerLocation.blobUrl(blobName);
    }

    private JsonNode requestUploadUrl(UUID userId, String contentType, long sizeBytes) throws Exception {
        var content = mockMvc.perform(authenticated(post(UPLOAD_URL_PATH), userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "contentType", contentType,
                                "sizeBytes", sizeBytes))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return readJson(content);
    }

    private JsonNode getJson(String path, UUID userId) throws Exception {
        return readJson(mockMvc.perform(authenticated(get(path), userId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private HttpResponse<String> putBlob(String uploadUrl, byte[] bytes) throws Exception {
        return httpClient.send(HttpRequest.newBuilder(URI.create(uploadUrl))
                        .header("x-ms-blob-type", "BlockBlob")
                        .header("Content-Type", "image/jpeg")
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder request, UUID userId) {
        return request.with(jwt().jwt(token -> token.subject(userId.toString())));
    }

    private JsonNode readJson(String content) {
        return jsonMapper.readTree(content);
    }

    private UUID seedUser(String username, String pictureUrl) {
        var userId = UUID.randomUUID();
        seedUser(userId, username, pictureUrl);
        return userId;
    }

    private void seedUser(UUID userId, String username, String pictureUrl) {
        SocialIntegrationSeeder.seedUser(jpaUserRepository, UserEntity.builder()
                .id(userId)
                .username(username)
                .email(username + "@example.com")
                .pictureUrl(pictureUrl)
                .bio(UserBioEmbeddable.builder().description("Bio of " + username).socialMedia(Map.of()).build())
                .accountStatus(UserAccountStatus.ACCEPTED)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
    }
}
