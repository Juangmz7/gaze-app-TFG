package com.app.socialservice.user.application.dto;

/**
 * Read on every request, never cached: how the requester and the profile's user relate,
 * and whether that user is still visible (exists, neither deleted nor banned), so a ban
 * applies at once even while the profile is cached.
 */
public record ProfileRelationship(boolean following, boolean followsMe, boolean blocked, boolean targetVisible) {
}
