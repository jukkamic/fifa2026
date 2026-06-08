package dev.scaffoldkit.fifa.web;

import dev.scaffoldkit.fifa.model.UserProfile;

/**
 * ThreadLocal holder for the current request's {@link UserProfile}.
 * Populated by {@link LocalhostUserFilter} on every request.
 */
public final class UserContext {

    private static final ThreadLocal<UserProfile> CURRENT = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(UserProfile userProfile) {
        CURRENT.set(userProfile);
    }

    public static UserProfile get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}