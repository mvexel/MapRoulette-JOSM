// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.api.auth;

import org.openstreetmap.josm.plugins.maproulette.api.UnauthorizedException;
import org.openstreetmap.josm.tools.HttpClient;

/**
 * Provides the MapRoulette credentials for requests
 */
public interface AuthProvider {
    /**
     * Used while MapRoulette has no login that JOSM can use: requests are sent without credentials
     */
    AuthProvider NONE = new AuthProvider() {
        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public void authenticate(HttpClient client) {
            // Nothing to add
        }
    };

    /**
     * Check if requests that need a MapRoulette login (start, stop and task status) can be made
     *
     * @return {@code true} if the user can log in to MapRoulette
     */
    boolean isAvailable();

    /**
     * Add the credentials to a request. A provider that adds a token must only add it to https requests to the
     * configured MapRoulette server's host.
     *
     * @param client The client to add the credentials to
     * @throws UnauthorizedException if the user is not logged in
     */
    void authenticate(HttpClient client) throws UnauthorizedException;
}
