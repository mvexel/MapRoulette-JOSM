// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.api.client;

import java.io.Serial;

/**
 * The request needs a MapRoulette login, and there is none (HTTP 401 or 410, or no login is available)
 */
public class MapRouletteAuthUnavailableException extends MapRouletteException {
    @Serial
    private static final long serialVersionUID = -2364307373478417094L;

    /**
     * Create a new exception
     *
     * @param message The message
     */
    public MapRouletteAuthUnavailableException(String message) {
        super(message);
    }
}
