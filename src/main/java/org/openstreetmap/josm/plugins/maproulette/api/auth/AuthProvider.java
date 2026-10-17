// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.api.auth;

import java.util.Collections;
import java.util.OptionalLong;
import java.util.Set;

import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteAuthUnavailableException;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteException;
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
        public void authenticate(HttpClient client, boolean loginRequired) {
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
     * Add the credentials to a request. Called for every request. This must not do IO on the EDT. A provider that
     * adds a token must only add it to https requests to the configured MapRoulette server's host.
     *
     * @param client        The client to add the credentials to
     * @param loginRequired {@code true} for requests that need a login (writes). Reads should go without a token:
     *                      MapRoulette refuses app tokens on most read routes.
     * @throws MapRouletteAuthUnavailableException if the request needs a login and the user is not logged in
     */
    void authenticate(HttpClient client, boolean loginRequired) throws MapRouletteAuthUnavailableException;

    /**
     * Called once after MapRoulette answers 401
     *
     * @param failed The request that got the 401, with the credentials it was sent with
     * @return {@code true} if the credentials were refreshed and the request may be sent again (once)
     */
    default boolean onUnauthorized(HttpClient failed) {
        return false;
    }

    /**
     * Get the MapRoulette id of the logged-in user. The first call after a login asks MapRoulette, so call it off the
     * EDT.
     *
     * @return The id, or empty if nobody is logged in
     * @throws MapRouletteException if MapRoulette could not be asked
     */
    default OptionalLong userId() throws MapRouletteException {
        return OptionalLong.empty();
    }

    /**
     * Get the MapRoulette id of the logged-in user if {@link #userId()} has looked it up already. This does no IO.
     *
     * @return The id, or empty if it is not known
     */
    default OptionalLong knownUserId() {
        return OptionalLong.empty();
    }
}
