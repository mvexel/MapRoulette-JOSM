// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.api.parsers;

import static org.openstreetmap.josm.plugins.maproulette.api.parsers.ParsingUtils.has;

import jakarta.json.JsonObject;

import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteException;

/**
 * A parser for message objects. All methods can throw a {@link MapRouletteException}!
 */
final class MessageParser {
    private MessageParser() {
        // Hide constructor
    }

    /**
     * Parse a potential API message
     *
     * @param jsonObject The object to look at
     * @throws MapRouletteException If the object is an error message
     */
    static void parse(JsonObject jsonObject) throws MapRouletteException {
        if (has(jsonObject, "status") && has(jsonObject, "message")) {
            throw new MapRouletteException("MapRoulette error: " + jsonObject.getString("message"));
        }
    }
}
