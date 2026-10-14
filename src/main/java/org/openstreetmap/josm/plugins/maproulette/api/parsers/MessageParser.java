// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.api.parsers;

import static org.openstreetmap.josm.plugins.maproulette.api.parsers.ParsingUtils.has;

import jakarta.json.JsonObject;

import org.openstreetmap.josm.plugins.maproulette.api.UnauthorizedException;

/**
 * A parser for message objects. All methods can throw an {@link UnauthorizedException}!
 */
final class MessageParser {
    private MessageParser() {
        // Hide constructor
    }

    /**
     * Parse a potential API message
     *
     * @param jsonObject The object to look at
     * @throws UnauthorizedException If there is a problem
     */
    static void parse(JsonObject jsonObject) throws UnauthorizedException {
        if (has(jsonObject, "status") && has(jsonObject, "message")) {
            throw new UnauthorizedException(jsonObject.getString("message"));
        }
    }
}
