// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.api.parsers;

import java.io.Reader;

import org.openstreetmap.josm.plugins.maproulette.api.model.PublicOSMUser;
import org.openstreetmap.josm.plugins.maproulette.api.model.PublicUser;

import jakarta.annotation.Nonnull;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;

/**
 * A parser for the public user objects of {@code /user/:id/public}
 */
public final class PublicUserParser {
    private PublicUserParser() {
        // Hide constructor
    }

    /**
     * Parse a public user from a response body
     *
     * @param body The response body
     * @return The parsed user
     */
    @Nonnull
    public static PublicUser parse(Reader body) {
        try (final var reader = Json.createReader(body)) {
            final var structure = reader.read();
            if (structure.getValueType() == JsonValue.ValueType.OBJECT
                    && structure.asJsonObject().get("osmProfile")instanceof JsonObject profile) {
                final var obj = structure.asJsonObject();
                return new PublicUser(obj.getJsonNumber("id").longValue(),
                        new PublicOSMUser(profile.getJsonNumber("id").longValue(), profile.getString("avatarURL", null),
                                profile.getString("displayName")),
                        obj.getString("name", profile.getString("displayName")), null, null);
            }
            throw new IllegalArgumentException("Could not parse public user JSON: " + structure);
        }
    }
}
