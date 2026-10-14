// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

import jakarta.json.Json;
import javax.swing.text.html.Option;

import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteClient;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteParseException;
import org.openstreetmap.josm.plugins.maproulette.api.enums.TaskStatus;
import org.openstreetmap.josm.plugins.maproulette.api.model.ClusteredPoint;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.plugins.maproulette.api.parsers.ClusteredPointParser;
import org.openstreetmap.josm.plugins.maproulette.api.parsers.TaskParser;

/**
 * Task API
 */
public final class TaskAPI {
    /**
     * The basic task api endpoint
     */
    private static final String TASK = "/task";
    /**
     * The basic tasks api endpoint
     */
    private static final String TASKS = TASK + "s";

    /**
     * Don't allow instantiation of this utility class
     */
    private TaskAPI() {
        // Hide constructor
    }

    /**
     * Get tasks in a bounding box
     *
     * @param minLat            The minimum latitude for the bounding box (left)
     * @param minLon            The minimum longitude for the bounding box (bottom)
     * @param maxLat            The maximum latitude for the bounding box (right)
     * @param maxLon            The maximum longitude for the bounding box (top)
     * @param limit             Limit the number of results returned in the response
     * @param page              Used to paginate through the responses
     * @param excludeLocked     {@code true} will ignore the lock status of tasks
     * @param sort              Unknown
     * @param order             ASC/DESC
     * @param includeTotal      Include total
     * @param includeGeometries include the geometries
     * @param includeTags       include the tags
     * @return The collection of tasks
     * @throws IOException if there was a problem communicating with the server, or its response could not be read
     */
    public static ClusteredPoint[] box(double minLon, double minLat, double maxLon, double maxLat, int limit, int page,
            boolean excludeLocked, String sort, String order, boolean includeTotal, boolean includeGeometries,
            boolean includeTags) throws IOException {
        Map<String, String> query = new TreeMap<>();
        if (limit > 0) {
            query.put("limit", String.valueOf(limit));
        }
        if (page > 0) {
            query.put("page", String.valueOf(page));
        }
        if (excludeLocked) {
            query.put("excludeLocked", "true");
        }
        if (sort != null && !sort.isBlank()) {
            query.put("sort", sort);
        }
        if (order != null && !order.isBlank()) {
            query.put("order", order);
        }
        if (includeTotal) {
            query.put("includeTotal", "true");
        }
        if (includeGeometries) {
            query.put("includeGeometries", "true");
        }
        if (includeTags) {
            query.put("includeTags", "true");
        }
        final var path = TASKS + "/box/" + minLon + "/" + minLat + "/" + maxLon + "/" + maxLat;
        final var result = MapRouletteClient.getInstance().putForm(path, query, ClusteredPointParser::parse);
        if (result instanceof ClusteredPoint[] points) {
            return points;
        }
        // Let the caller tell the user, instead of silently showing no tasks
        throw new MapRouletteParseException("Unexpected MapRoulette response from " + path,
                new IllegalArgumentException(String.valueOf(result)));
    }

    /**
     * Get a task without locking it
     *
     * @param task The task to get
     * @return The task for the id
     * @throws IOException if there was a problem communicating with the server
     */
    public static Task get(long task) throws IOException {
        return MapRouletteClient.getInstance().get(TASK + "/" + task, Collections.emptyMap(),
                body -> (Task) TaskParser.parseTask(body));
    }

    /**
     * Start and lock a task
     *
     * @param task The task to start
     * @return The updated task
     * @throws IOException if there was a problem communicating with the server
     * @throws org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteAuthUnavailableException if the user
     *         can't log in to MapRoulette
     * @throws org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteLockHeldException if the user already
     *         holds another task
     */
    public static Task start(long task) throws IOException {
        final var client = MapRouletteClient.getInstance();
        client.requireAuth();
        return client.get(TASK + "/" + task + "/start", Collections.emptyMap(),
                body -> (Task) TaskParser.parseTask(body));
    }

    /**
     * Release a tasks lock (the user must hold the lock)
     *
     * @param task The task to unlock
     * @return The unlocked task
     * @throws IOException if there was a problem communicating with the server
     */
    public static Task release(long task) throws IOException {
        final var client = MapRouletteClient.getInstance();
        client.requireAuth();
        return client.get(TASK + "/" + task + "/release", Collections.emptyMap(),
                body -> (Task) TaskParser.parseTask(body));
    }

    /**
     * Refresh an existing lock on a task
     *
     * @param task The task to update
     * @return The updated task
     * @throws IOException if there was a problem communicating with the server
     */
    public static Task refreshLock(long task) throws IOException {
        final var client = MapRouletteClient.getInstance();
        client.requireAuth();
        return client.get(TASK + "/" + task + "/refreshLock", Collections.emptyMap(),
                body -> (Task) TaskParser.parseTask(body));
    }

    /**
     * Update the changeset for a task
     *
     * @param task the task to update the changeset for
     * @return The updated task
     * @throws IOException if there was a problem communicating with the server
     */
    public static Task changeset(long task) throws IOException {
        final var client = MapRouletteClient.getInstance();
        client.requireAuth();
        return client.put(TASK + "/" + task + "/changeset", Collections.emptyMap(), null,
                body -> (Task) TaskParser.parseTask(body));
    }

    /**
     * Update the status of a task
     *
     * @param task          The task to update
     * @param status        The status to set
     * @param comment       The comment to use
     * @param tags          The tags to use
     * @param requestReview Request review (or not), overrides user settings
     * @param completionResponses The completion responses
     * @return {@code true}; failures throw
     * @throws IOException if there was a problem communicating with the server
     */
    public static boolean updateStatus(long task, TaskStatus status, String comment, String tags, Boolean requestReview,
            Map<String, Option> completionResponses) throws IOException {
        Map<String, String> query = new TreeMap<>();
        if (comment != null && !comment.isBlank()) {
            query.put("comment", comment);
        }
        if (tags != null && !tags.isBlank()) {
            query.put("tags", tags);
        }
        if (requestReview != null) {
            query.put("requestReview", requestReview.toString());
        }
        final byte[] body;
        if (completionResponses != null) {
            var jsonBuilder = Json.createObjectBuilder();
            for (Map.Entry<String, Option> entry : completionResponses.entrySet()) {
                jsonBuilder.add(entry.getKey(), entry.getValue().getValue());
            }
            body = jsonBuilder.build().toString().getBytes(StandardCharsets.UTF_8);
        } else {
            body = null;
        }
        final var client = MapRouletteClient.getInstance();
        client.requireAuth();
        client.put(TASK + "/" + task + "/" + status.code(), query, body, null);
        return true;
    }
}
