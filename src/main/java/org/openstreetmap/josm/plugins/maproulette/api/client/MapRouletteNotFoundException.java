// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.api.client;

import java.io.Serial;

/**
 * MapRoulette does not have the requested object (HTTP 404)
 */
public class MapRouletteNotFoundException extends MapRouletteException {
    @Serial
    private static final long serialVersionUID = 5096167711451346385L;

    /**
     * Create a new exception
     *
     * @param message The message
     */
    public MapRouletteNotFoundException(String message) {
        super(message);
    }
}
