// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.api;

import java.io.IOException;
import java.util.Collections;

import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteClient;
import org.openstreetmap.josm.plugins.maproulette.api.model.PublicUser;
import org.openstreetmap.josm.plugins.maproulette.api.parsers.PublicUserParser;

import jakarta.annotation.Nonnull;

/**
 * A class for getting data from User APIs
 */
public final class UserAPI {
    private UserAPI() {
        // Hide constructor
    }

    /**
     * Get the public profile of a user, without a login: the JOSM login gets 403 on this route
     *
     * @param osmId The user's OSM id, as in a challenge's {@code owner}
     * @return The user. MapRoulette falls back to its own user ids when no user has this OSM id, so check
     *         {@code osmProfile().id()}.
     * @throws IOException if there was a problem communicating with the server
     */
    @Nonnull
    public static PublicUser publicUser(long osmId) throws IOException {
        return MapRouletteClient.getInstance().get("/user/" + osmId + "/public", Collections.emptyMap(),
                PublicUserParser::parse);
    }
}
