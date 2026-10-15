// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.api.client;

import java.io.IOException;
import java.io.Serial;

/**
 * A problem talking to MapRoulette. Subclasses say what went wrong; this class itself wraps connection problems.
 */
public class MapRouletteException extends IOException {
    @Serial
    private static final long serialVersionUID = 4212381364151946232L;

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
}
