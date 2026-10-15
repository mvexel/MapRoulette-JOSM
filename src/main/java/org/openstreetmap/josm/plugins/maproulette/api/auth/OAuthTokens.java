// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.api.auth;

import java.io.StringReader;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.json.Json;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;

/**
 * The tokens of a MapRoulette login
 *
 * @param accessToken  The bearer token for requests
 * @param refreshToken The token to get a new pair with; it can be used only once
 * @param expiresAt    When the access token expires
 * @param scope        The scopes MapRoulette granted
 */
record OAuthTokens(@Nonnull String accessToken, @Nonnull String refreshToken, @Nonnull Instant expiresAt,
        @Nonnull Set<String> scope) {

    OAuthTokens {
        Objects.requireNonNull(accessToken, "accessToken");
        Objects.requireNonNull(refreshToken, "refreshToken");
        Objects.requireNonNull(expiresAt, "expiresAt");
        scope = Set.copyOf(scope);
    }

    /**
     * Read a token response from the MapRoulette token endpoint
     *
     * @param response The response body
     * @param now      The time the response arrived
     * @return The tokens
     * @throws JsonException if the response is not a bearer token response with a refresh token
     */
    static OAuthTokens fromTokenResponse(String response, Instant now) {
        final JsonObject json = parse(response);
        if (!"bearer".equalsIgnoreCase(json.getString("token_type", ""))) {
            throw new JsonException("Not a bearer token");
        }
        return new OAuthTokens(json.getString("access_token"), json.getString("refresh_token"),
                now.plusSeconds(json.getJsonNumber("expires_in").longValueExact()), scopes(json.getString("scope", "")));
    }

    /**
     * Read stored tokens
     *
     * @param stored The value from {@link #toStorage()}
     * @return The tokens, or {@code null} if nothing usable was stored
     */
    @Nullable
    static OAuthTokens fromStorage(@Nullable String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        try {
            final JsonObject json = parse(stored);
            return new OAuthTokens(json.getString("access_token"), json.getString("refresh_token"),
                    Instant.ofEpochSecond(json.getJsonNumber("expires_at").longValueExact()),
                    scopes(json.getString("scope", "")));
        } catch (JsonException | ClassCastException | NullPointerException | ArithmeticException e) {
            return null;
        }
    }

    /**
     * Convert the tokens for storage
     *
     * @return The value to store
     */
    String toStorage() {
        return Json.createObjectBuilder().add("access_token", accessToken).add("refresh_token", refreshToken)
                .add("expires_at", expiresAt.getEpochSecond()).add("scope", String.join(" ", scope)).build()
                .toString();
    }

    /**
     * Check if the access token is about to expire
     *
     * @param now The current time
     * @return {@code true} if the access token should be refreshed before it is used
     */
    boolean expiresSoon(Instant now) {
        return !now.plusSeconds(60).isBefore(expiresAt);
    }

    @Override
    public String toString() {
        // Never put the tokens in logs or bug reports
        return "OAuthTokens[expiresAt=" + expiresAt + ", scope=" + scope + ']';
    }

    private static JsonObject parse(String body) {
        try (var reader = Json.createReader(new StringReader(body))) {
            return reader.readObject();
        }
    }

    private static Set<String> scopes(String scope) {
        return Arrays.stream(scope.split(" ", -1)).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }
}
