package com.app.socialservice.shared.application.media;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.app.socialservice.block.application.dto.BlockedUserResponse;
import com.app.socialservice.user.application.dto.OwnUserProfileResponse;
import com.app.socialservice.user.application.dto.RecommendedUserResponse;
import com.app.socialservice.user.application.dto.UserProfileResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PictureUrlResponseSignerTest {

    private static final String BARE = "https://account.blob.core.windows.net/post-media/avatars/u/p.jpg";
    private static final String SIGNED = BARE + "?sp=r&sig=abc";

    private final PictureUrlResponseSigner signer = new PictureUrlResponseSigner(
            url -> BARE.equals(url) ? SIGNED : url);

    @Test
    void shouldSignThePictureOfEveryProfileResponseAndKeepTheOtherFields() {
        var userId = UUID.randomUUID();

        var own = signer.sign(new OwnUserProfileResponse(userId, "me", "bio", null, 1, 2, 3, BARE));
        var profile = signer.sign(new UserProfileResponse(userId, "them", null, null, 4, 5, 6, BARE, true, false));

        assertThat(own).isEqualTo(new OwnUserProfileResponse(userId, "me", "bio", null, 1, 2, 3, SIGNED));
        assertThat(profile)
                .isEqualTo(new UserProfileResponse(userId, "them", null, null, 4, 5, 6, SIGNED, true, false));
    }

    @Test
    void shouldSignEveryItemOfAListAndLeaveNullAndForeignPicturesAlone() {
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();

        var recommended = signer.signAll(List.of(
                new RecommendedUserResponse(first, "a", null, BARE, true),
                new RecommendedUserResponse(second, "b", null, null, false)));
        var blocked = signer.signAll(List.of(new BlockedUserResponse(first, "a", "https://cdn.example.com/a.png")));

        assertThat(recommended).extracting(RecommendedUserResponse::profilePic).containsExactly(SIGNED, null);
        assertThat(blocked).extracting(BlockedUserResponse::profilePic)
                .containsExactly("https://cdn.example.com/a.png");
    }

    @Test
    void shouldRejectNullResponses() {
        assertThatThrownBy(() -> signer.sign((OwnUserProfileResponse) null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> signer.signAll((List<BlockedUserResponse>) null))
                .isInstanceOf(NullPointerException.class);
    }
}
