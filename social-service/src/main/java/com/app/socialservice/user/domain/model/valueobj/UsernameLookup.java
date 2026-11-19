package com.app.socialservice.user.domain.model.valueobj;

import java.util.Locale;
import java.util.regex.Pattern;

import com.app.socialservice.user.domain.exception.InvalidUsernameLookupException;

/**
 * A username sent to find a profile (an @mention, a tagged user). Keycloak usernames are
 * lowercase letters, digits, '_' and '.'; the lookup is case-insensitive, so upper case is
 * accepted too and the value is kept in lower case, the form the lookup compares.
 */
public record UsernameLookup(String value) {

    public static final int MIN_LENGTH = 1;
    public static final int MAX_LENGTH = 64;

    private static final Pattern USERNAME_CHARSET = Pattern.compile("[A-Za-z0-9_.]+");

    public UsernameLookup {
        if (value == null || value.length() < MIN_LENGTH || value.length() > MAX_LENGTH) {
            throw invalidUsername();
        }
        if (!USERNAME_CHARSET.matcher(value).matches()) {
            throw invalidUsername();
        }
        value = value.toLowerCase(Locale.ROOT);
    }

    private static InvalidUsernameLookupException invalidUsername() {
        return new InvalidUsernameLookupException(String.format(
                "username must be %d to %d characters long and contain only letters, digits, '_' and '.'",
                MIN_LENGTH,
                MAX_LENGTH
        ));
    }
}
