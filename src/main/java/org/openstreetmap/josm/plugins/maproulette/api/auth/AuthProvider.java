// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.api.auth;

import java.util.Collections;
import java.util.Set;

import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteAuthUnavailableException;
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
        public Set<Capability> capabilities() {
            return Collections.emptySet();
        }

        @Override
        public void authenticate(HttpClient client) {
            // Nothing to add
        }
    };

    /**
     * What a login lets the user do. The OAuth scopes MapRoulette grants differ per server.
     */
    enum Capability {
        /** Start (lock) a task */
        LOCK,
        /** Set a task status */
        SET_STATUS,
        /** Comment on a task */
        COMMENT,
        /** Refresh a task lock */
        REFRESH_LOCK,
        /** Create and change task bundles */
        BUNDLE
    }

    /**
     * Check if requests that need a MapRoulette login (start, stop and task status) can be made
     *
     * @return {@code true} if the user can log in to MapRoulette
     */
    boolean isAvailable();

    /**
     * Get what the login allows
     *
     * @return The capabilities; empty if {@link #isAvailable()} is {@code false}
     */
    Set<Capability> capabilities();

    /**
     * Add the credentials to a request. This must not do IO on the EDT. A provider that adds a token must only add it
     * to https requests to the configured MapRoulette server's host.
     *
     * @param client The client to add the credentials to
     * @throws MapRouletteAuthUnavailableException if the user is not logged in
     */
    void authenticate(HttpClient client) throws MapRouletteAuthUnavailableException;

    /**
     * Called once after MapRoulette answers 401
     *
     * @return {@code true} if the credentials were refreshed and the request may be sent again (once)
     */
    default boolean onUnauthorized() {
        return false;
    }
}
