package com.app.postcommandservice.post.domain.model.valueobj;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Collections;

import org.springframework.util.StringUtils;

import com.app.postcommandservice.post.domain.exception.InvalidPostTagException;

public record PostTags(Set<String> value) {

    public PostTags {
        if (value == null) {
            throw new InvalidPostTagException("Post tags must not be null");
        }

        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String tag : value) {
            if (!StringUtils.hasText(tag)) {
                throw new InvalidPostTagException("Post tags must not be blank");
            }

            String normalizedTag = tag.trim();
            if (normalizedTag.length() > 50) {
                throw new InvalidPostTagException("Post tags must be lower than or equal to 50");
            }
            normalized.add(normalizedTag);
        }

        value = Collections.unmodifiableSet(normalized);
    }
}
