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
     * {@code true} if MapRoulette said what it doesn't have, {@code false} for a bare 404
     */
    private final boolean recordMissing;

    /**
     * Create a new exception
     *
     * @param message The message
     */
    public MapRouletteNotFoundException(String message) {
        this(message, false);
    }

    /**
     * Create a new exception
     *
     * @param message       The message
     * @param recordMissing {@code true} if MapRoulette's answer says the object doesn't exist
     */
    public MapRouletteNotFoundException(String message, boolean recordMissing) {
        super(message);
        this.recordMissing = recordMissing;
    }

    /**
     * Check whether MapRoulette said the object doesn't exist. Its controllers answer a missing object with a JSON
     * {@code {"status":"NotFound","message":...}} body. A bare 404 with no body comes from the app login's preview
     * mode instead, which hides the tasks of an unpublished challenge (#116).
     *
     * @return {@code true} if the object doesn't exist
     */
    public boolean recordMissing() {
        return this.recordMissing;
    }
}
