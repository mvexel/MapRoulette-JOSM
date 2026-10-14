// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.api.parsers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import jakarta.json.Json;
import jakarta.json.JsonObject;

/**
 * Test class for {@link ParsingUtils}
 */
class ParsingUtilsTest {
    private static JsonObject json(String json) {
        try (var reader = Json.createReader(new StringReader(json))) {
            return reader.readObject();
        }
    }

    @Test
    void testHas() {
        final var object = json("{\"present\": 1, \"nullValue\": null}");
        assertTrue(ParsingUtils.has(object, "present"));
        assertFalse(ParsingUtils.has(object, "nullValue"));
        assertFalse(ParsingUtils.has(object, "missing"));
    }

    /**
     * MapRoulette sends {@code null} for some absent values; the optional accessors must treat it like a missing key
     */
    @Test
    void testOptionalAccessorsTreatNullAsAbsent() {
        final var object = json("{\"o\": null, \"a\": null, \"i\": null, \"l\": null, \"n\": null}");
        assertNull(ParsingUtils.optionalObject(object, "o", JsonObject::size));
        assertNull(ParsingUtils.optionalArray(object, "a", array -> array.size()));
        assertNull(ParsingUtils.optionalInstant(object, "i"));
        assertNull(ParsingUtils.optionalLong(object, "l"));
        assertNull(ParsingUtils.optionalInteger(object, "n"));
    }

    @Test
    void testOptionalAccessorsReadValues() {
        final var object = json(
                "{\"o\": {\"x\": 1}, \"a\": [1, 2], \"i\": \"2024-01-02T03:04:05Z\", \"l\": 12345678901, \"n\": 7}");
        assertEquals(1, ParsingUtils.<Integer> optionalObject(object, "o", JsonObject::size));
        assertEquals(2, ParsingUtils.<Integer> optionalArray(object, "a", array -> array.size()));
        assertEquals(Instant.parse("2024-01-02T03:04:05Z"), ParsingUtils.optionalInstant(object, "i"));
        assertEquals(12345678901L, ParsingUtils.optionalLong(object, "l"));
        assertEquals(7, ParsingUtils.optionalInteger(object, "n"));
    }
}
