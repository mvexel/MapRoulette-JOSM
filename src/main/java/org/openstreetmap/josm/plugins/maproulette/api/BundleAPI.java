// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.api;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteClient;
import org.openstreetmap.josm.plugins.maproulette.api.model.TaskBundle;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
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
     * @param name      The bundle name
     * @param primaryId The task the bundle is worked from, if any
     * @param taskIds   The tasks to bundle
     * @return The created bundle
     * @throws IOException if there was a problem communicating with the server
     */
    @Nonnull
    public static TaskBundle createBundle(String name, @Nullable Long primaryId, long... taskIds) throws IOException {
        final var body = Json.createObjectBuilder().add("name", name);
        if (primaryId != null) {
            body.add("primaryId", primaryId);
        }
        final var ids = Json.createArrayBuilder();
        LongStream.of(taskIds).forEach(ids::add);
        body.add("taskIds", ids);
        return MapRouletteClient.getInstance().post(PATH, Collections.emptyMap(),
                body.build().toString().getBytes(StandardCharsets.UTF_8), BundleAPI::parseBundle);
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
                // MapRoulette sends the id as bundleId and no name
                final var id = obj.containsKey("bundleId") ? obj.getJsonNumber("bundleId") : obj.getJsonNumber("id");
                return new TaskBundle(id.longValue(), obj.getString("name", ""),
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
        // MapRoulette reads bundles with POST
        return MapRouletteClient.getInstance().post(PATH + "/" + id, Collections.emptyMap(), null,
                BundleAPI::parseBundle);
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
        // MapRoulette wants each id as its own taskIds parameter, which the query map can't hold; ids need no encoding
        final var query = LongStream.of(taskIds).mapToObj(id -> "taskIds=" + id)
                .collect(Collectors.joining("&", "?", ""));
        return MapRouletteClient.getInstance().post(PATH + "/" + original.id() + "/unbundle" + query,
                Collections.emptyMap(), null, BundleAPI::parseBundle);
    }
}
