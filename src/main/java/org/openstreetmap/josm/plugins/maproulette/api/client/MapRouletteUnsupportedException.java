// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.api.client;

import java.io.Serial;

/**
 * The MapRoulette server or the login does not allow this request from JOSM (HTTP 403 {@code insufficient_scope}), for
 * example task bundles on servers that do not allow them for app logins yet
 */
public class MapRouletteUnsupportedException extends MapRouletteForbiddenException {
    @Serial
    private static final long serialVersionUID = 4419275903318246471L;

    /**
     * Create a new exception
     *
     * @param message The message
     */
    public MapRouletteUnsupportedException(String message) {
        super(message);
    }
}
