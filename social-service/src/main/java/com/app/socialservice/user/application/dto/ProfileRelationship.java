package com.app.socialservice.user.application.dto;

/** How the requester and the profile's user relate: read on every request, never cached. */
public record ProfileRelationship(boolean following, boolean followsMe, boolean blocked) {
}
