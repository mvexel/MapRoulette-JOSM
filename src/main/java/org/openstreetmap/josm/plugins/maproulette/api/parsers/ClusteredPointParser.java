// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.api.parsers;

import static org.openstreetmap.josm.plugins.maproulette.api.parsers.ParsingUtils.has;
import static org.openstreetmap.josm.plugins.maproulette.api.parsers.ParsingUtils.optionalInstant;
import static org.openstreetmap.josm.plugins.maproulette.api.parsers.ParsingUtils.optionalLong;
import static org.openstreetmap.josm.plugins.maproulette.api.parsers.ParsingUtils.optionalObject;

import java.io.Reader;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import org.openstreetmap.josm.io.IllegalDataException;
import org.openstreetmap.josm.plugins.maproulette.api.enums.Difficulty;
import org.openstreetmap.josm.plugins.maproulette.api.enums.TaskStatus;
import org.openstreetmap.josm.plugins.maproulette.api.model.ClusteredPoint;
import org.openstreetmap.josm.tools.JosmRuntimeException;

import jakarta.annotation.Nullable;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import jakarta.json.stream.JsonParser;

/**
 * Parser for {@link org.openstreetmap.josm.plugins.maproulette.api.model.ClusteredPoint}
 */
public final class ClusteredPointParser {
    /**
     * Prevent instantiation of this parser
     */
    private ClusteredPointParser() {
        // Hide constructor
    }

    /**
     * Parse {@link org.openstreetmap.josm.plugins.maproulette.api.model.ClusteredPoint}s from a response body
     *
     * @param body The response body
     * @return The {@link org.openstreetmap.josm.plugins.maproulette.api.model.ClusteredPoint}(s). Either an array or a single point.
     */
    public static Object parse(Reader body) {
        try (JsonParser parser = Json.createParser(body)) {
            while (parser.hasNext()) {
                switch (parser.next()) {
                case START_ARRAY:
                    return parser.getArrayStream().map(ClusteredPointParser::parse).filter(Objects::nonNull)
                            .toArray(ClusteredPoint[]::new);
                case START_OBJECT:
                    return parse(parser.getObject());
                default:
                    // Keep going
                }
            }
        }
        throw new IllegalArgumentException("The response did not contain a valid ClusteredPoint");
    }

    /**
     * Create a new point from a value
     *
     * @param value The value object
     * @return The point
     */
    @Nullable
    private static ClusteredPoint parse(JsonValue value) {
        if (value instanceof JsonObject object) {
            return new ClusteredPoint(object.getJsonNumber("id").longValue(), object.getJsonNumber("owner").longValue(),
                    object.getString("ownerName"), object.getString("title"),
                    object.getJsonNumber("parentId").longValue(), object.getString("parentName"),
                    Objects.requireNonNull(PointParser.parse(object.getJsonObject("point"))),
                    parseBounding(object.get("bounding")), object.getString("blurb"),
                    Instant.parse(object.getString("modified")),
                    has(object, "difficulty") && object.getInt("difficulty") > 0
                            ? Difficulty.fromCode(object.getInt("difficulty"))
                            : null,
                    object.getInt("type"), has(object, "status") ? TaskStatus.fromCode(object.getInt("status")) : null,
                    object.getString("cooperativeWork", null), optionalInstant(object, "mappedOn"),
                    optionalLong(object, "completedTimeSpent"),
                    optionalObject(object, "completedBy", UserParser::parse),
                    PointReviewParser.parse(object.getJsonObject("pointReview")), object.getInt("priority", 0),
                    optionalLong(object, "bundleId"), object.getBoolean("isBundlePrimary", false),
                    optionalLong(object, "lockedBy"), parseProperties(object.get("geometries")));
        }
        return null;
    }

    /**
     * Get the properties of the first feature that has any, sent with {@code includeGeometries=true}
     *
     * @param geometries The GeoJSON feature collection, if any
     * @return The properties, with every value as text
     */
    static Map<String, String> parseProperties(@Nullable JsonValue geometries) {
        if (geometries instanceof JsonObject collection && collection.get("features")instanceof JsonArray features) {
            for (var feature : features) {
                if (feature instanceof JsonObject featureObject
                        && featureObject.get("properties")instanceof JsonObject properties && !properties.isEmpty()) {
                    final var map = new TreeMap<String, String>();
                    properties.forEach((key, value) -> {
                        if (value instanceof JsonString str) {
                            map.put(key, str.getString());
                        } else if (value.getValueType() != JsonValue.ValueType.NULL) {
                            map.put(key, value.toString());
                        }
                    });
                    return Collections.unmodifiableMap(map);
                }
            }
        }
        return Collections.emptyMap();
    }

    @Nullable
    private static Object parseBounding(@Nullable JsonValue bounding) {
        if (bounding == null || bounding.getValueType() == JsonValue.ValueType.NULL) {
            // See JOSM #24772: MapRoulette may send "bounding": null, or leave it out
            return null;
        } else if (bounding instanceof JsonString str) {
            return str.getString();
        } else if (bounding instanceof JsonObject obj) {
            if (obj.isEmpty()) {
                return null;
            }
            try {
                return GeometryParser.parseValue(obj);
            } catch (IllegalDataException ide) {
                throw new JosmRuntimeException(bounding.toString(), ide);
            }
        }
        throw new IllegalArgumentException(bounding.toString());
    }
}
