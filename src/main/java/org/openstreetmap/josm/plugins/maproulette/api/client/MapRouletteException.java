// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.api.client;

import java.io.IOException;
import java.io.Serial;
import java.util.Optional;

/**
 * A problem talking to MapRoulette. Subclasses say what went wrong; this class itself wraps connection problems.
 */
public class MapRouletteException extends IOException {
    @Serial
    private static final long serialVersionUID = 4212381364151946232L;
    /**
     * The {@code error} code of MapRoulette's answer, if it gave one
     */
    private String errorCode;

    /**
     * Create a new exception
     *
     * @param message The message
     */
    public MapRouletteException(String message) {
        super(message);
    }

    /**
     * Create a new exception
     *
     * @param message The message
     * @param cause   The cause
     */
    public MapRouletteException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Get the {@code error} code of MapRoulette's answer, such as {@code changeset_open}
     *
     * @return The code, if MapRoulette gave one
     */
    public Optional<String> errorCode() {
        return Optional.ofNullable(this.errorCode);
    }

    /**
     * Set the {@code error} code of MapRoulette's answer
     *
     * @param errorCode The code, or {@code null}
     * @return This exception
     */
    MapRouletteException withErrorCode(String errorCode) {
        this.errorCode = errorCode;
        return this;
    }
}
