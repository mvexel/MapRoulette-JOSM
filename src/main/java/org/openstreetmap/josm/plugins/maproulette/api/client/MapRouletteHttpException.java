// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.api.client;

import java.io.Serial;
import java.util.Optional;

import jakarta.annotation.Nullable;

/**
 * MapRoulette answered with an HTTP error that has no more specific exception
 */
public class MapRouletteHttpException extends MapRouletteException {
    @Serial
    private static final long serialVersionUID = -1840719357211493946L;
    /**
     * The HTTP status
     */
    private final int status;
    /**
     * The start of the response body
     */
    private final String bodyExcerpt;
    /**
     * The message MapRoulette sent, if any
     */
    private final String serverMessage;

    /**
     * Create a new exception
     *
     * @param message     The message
     * @param status      The HTTP status
     * @param bodyExcerpt   The start of the response body
     * @param serverMessage The {@code message} of the response body, if any
     */
    public MapRouletteHttpException(String message, int status, String bodyExcerpt, @Nullable String serverMessage) {
        super(message);
        this.status = status;
        this.bodyExcerpt = bodyExcerpt;
        this.serverMessage = serverMessage;
    }

    /**
     * Get the HTTP status
     *
     * @return The status
     */
    public int status() {
        return this.status;
    }

    /**
     * Get the start of the response body
     *
     * @return The body excerpt (may be empty)
     */
    public String bodyExcerpt() {
        return this.bodyExcerpt;
    }

    /**
     * Get the {@code message} MapRoulette sent with the error
     *
     * @return The message, if the body has one
     */
    public Optional<String> serverMessage() {
        return Optional.ofNullable(this.serverMessage);
    }
}
