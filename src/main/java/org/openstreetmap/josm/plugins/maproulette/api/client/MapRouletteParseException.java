// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.api.client;

import java.io.Serial;

/**
 * The MapRoulette response could not be read
 */
public class MapRouletteParseException extends MapRouletteException {
    @Serial
    private static final long serialVersionUID = 7375683291064457795L;

    /**
     * Create a new exception
     *
     * @param message The message
     * @param cause   The cause
     */
    public MapRouletteParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
