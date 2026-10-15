// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.api.client;

import java.io.Serial;

/**
 * MapRoulette refused the request for this user (HTTP 403), for example because another user holds the task lock
 */
public class MapRouletteForbiddenException extends MapRouletteException {
    @Serial
    private static final long serialVersionUID = -3127617839506185313L;

    /**
     * Create a new exception
     *
     * @param message The message
     */
    public MapRouletteForbiddenException(String message) {
        super(message);
    }
}
