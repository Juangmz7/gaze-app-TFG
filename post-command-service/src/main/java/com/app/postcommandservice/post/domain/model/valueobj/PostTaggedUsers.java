package com.app.postcommandservice.post.domain.model.valueobj;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Collections;

import org.springframework.util.StringUtils;

import com.app.postcommandservice.post.domain.exception.InvalidTaggedUsernameException;

public record PostTaggedUsers(Set<String> value) {

    public PostTaggedUsers {
        if (value == null) {
            throw new InvalidTaggedUsernameException("Tagged users must not be null");
        }

        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String username : value) {
            if (!StringUtils.hasText(username)) {
                throw new InvalidTaggedUsernameException("Tagged usernames must not be blank");
            }

            String normalizedUsername = username.trim();
            if (normalizedUsername.length() > 50) {
                throw new InvalidTaggedUsernameException("Tagged usernames must be lower than or equal to 50");
            }
            normalized.add(normalizedUsername);
        }

        value = Collections.unmodifiableSet(normalized);
    }
}
