// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.api;

import java.io.IOException;
import java.io.Reader;
import java.util.Collections;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteClient;
import org.openstreetmap.josm.plugins.maproulette.api.model.TaskBundle;

import jakarta.annotation.Nonnull;
import jakarta.json.Json;
import jakarta.json.JsonNumber;
import jakarta.json.JsonReader;
import jakarta.json.JsonStructure;
import jakarta.json.JsonValue;

/**
 * A class for methods related to the bundle apis
 */
public final class BundleAPI {
    /**
     * The base task bundle path
     */
    private static final String PATH = "/taskBundle";

    /**
     * Hide the constructor for this API object
     */
    private BundleAPI() {
        // Hide constructor
    }

    /**
     * Calls POST /taskBundle
     *
     * @param bundle The bundle to create
     * @return The created bundle. This is <i>not</i> the same as the bundle sent in.
     * @throws IOException if there was a problem communicating with the server
     */
    @Nonnull
    public static TaskBundle createBundle(TaskBundle bundle) throws IOException {
        return MapRouletteClient.getInstance().post(PATH, Collections.emptyMap(), null, BundleAPI::parseBundle); // fixme add body
    }

    /**
     * Parse a {@link TaskBundle} from a response body
     *
     * @param bundle The response body with the bundle information
     * @return The bundle of tasks
     */
    @Nonnull
    public static TaskBundle parseBundle(Reader bundle) {
        try (JsonReader reader = Json.createReader(bundle)) {
            JsonStructure structure = reader.read();
            if (structure.getValueType() == JsonValue.ValueType.OBJECT) {
                var obj = structure.asJsonObject();
                return new TaskBundle(obj.getJsonNumber("id").longValue(), obj.getString("name"),
                        obj.getJsonNumber("ownerId").longValue(),
                        obj.getJsonArray("taskIds").stream().mapToLong(val -> ((JsonNumber) val).longValue()).toArray(),
                        null /* TODO parse tasks? */);
            } else {
                throw new IllegalArgumentException(structure.toString());
            }
        }
    }

    /**
     * Get a specified task bundle
     *
     * @param id The id of the bundle to get
     * @return The specified bundle
     * @throws IOException if there was a problem communicating with the server
     */
    @Nonnull
    public static TaskBundle getBundle(long id) throws IOException {
        return MapRouletteClient.getInstance().get(PATH + "/" + id, Collections.emptyMap(), BundleAPI::parseBundle);
    }

    /**
     * Delete a bundle
     *
     * @param id The bundle to delete
     * @return {@code true}; failures throw
     * @throws IOException if there was a problem communicating with the server
     */
    public static boolean deleteBundle(long id) throws IOException {
        MapRouletteClient.getInstance().delete(PATH + "/" + id);
        return true;
    }

    /**
     * Unbundle the specified tasks
     *
     * @param original The original bundle
     * @param taskIds  The tasks to remove
     * @return The new bundle
     * @throws IOException if there was a problem communicating with the server
     */
    @Nonnull
    public static TaskBundle unbundle(TaskBundle original, long... taskIds) throws IOException {
        final var client = MapRouletteClient.getInstance();
        return client.getWithLogin(PATH + "/" + original.id() + "/unbundle",
                Map.of("taskIds", LongStream.of(taskIds).mapToObj(Long::toString).collect(Collectors.joining(","))),
                BundleAPI::parseBundle);
    }
}
