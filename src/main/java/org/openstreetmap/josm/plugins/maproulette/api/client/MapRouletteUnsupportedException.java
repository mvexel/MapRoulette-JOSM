// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.api.client;

import java.io.Serial;
import java.util.Locale;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * The MapRoulette server refused the request for this login from JOSM (HTTP 403 with an {@code error} code), for
 * example task bundles on servers that do not allow them for app logins yet
 */
public class MapRouletteUnsupportedException extends MapRouletteForbiddenException {
    @Serial
    private static final long serialVersionUID = 4419275903318246471L;
    private final Reason reason;
    @Nullable
    private final String path;

    /**
     * Why the server refused the request
     */
    public enum Reason {
        /**
         * The login may not make this request ({@code insufficient_scope})
         */
        INSUFFICIENT_SCOPE,
        /**
         * The server has paused changes from apps ({@code mobile_writes_disabled})
         */
        MOBILE_WRITES_DISABLED,
        /**
         * The challenge is a draft that only its managers may change ({@code challenge_draft})
         */
        CHALLENGE_DRAFT,
        /**
         * The status of a multiple-choice task is set by answering it ({@code choice_task_status})
         */
        CHOICE_TASK_STATUS;

        /**
         * Find the reason for a server error code
         *
         * @param code The {@code error} of the response body
         * @return The reason, or {@code null} if the code is not one of these
         */
        @Nullable
        public static Reason fromCode(@Nullable String code) {
            if (code == null) {
                return null;
            }
            for (var reason : values()) {
                if (reason.name().toLowerCase(Locale.ROOT).equals(code)) {
                    return reason;
                }
            }
            return null;
        }
    }

    /**
     * Create a new exception
     *
     * @param message The message
     * @param reason  Why the server refused the request
     */
    public MapRouletteUnsupportedException(String message, @Nonnull Reason reason) {
        this(message, reason, null);
    }

    /**
     * Create a new exception
     *
     * @param message The message
     * @param reason  Why the server refused the request
     * @param path    The path of the refused request, if known
     */
    public MapRouletteUnsupportedException(String message, @Nonnull Reason reason, @Nullable String path) {
        super(message);
        this.reason = reason;
        this.path = path;
    }

    /**
     * Get the path of the refused request
     *
     * @return The path, or {@code null} if unknown
     */
    @Nullable
    public String path() {
        return this.path;
    }

    /**
     * Get why the server refused the request
     *
     * @return The reason
     */
    @Nonnull
    public Reason reason() {
        return this.reason;
    }
}
