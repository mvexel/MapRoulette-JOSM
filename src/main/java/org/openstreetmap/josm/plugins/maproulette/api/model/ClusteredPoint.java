// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.api.model;

import java.io.IOException;
import java.io.StringReader;
import java.time.Instant;
import java.util.Map;

import org.openstreetmap.josm.data.osm.BBox;
import org.openstreetmap.josm.plugins.maproulette.api.TaskAPI;
import org.openstreetmap.josm.plugins.maproulette.api.enums.Difficulty;
import org.openstreetmap.josm.plugins.maproulette.api.enums.TaskStatus;
import org.openstreetmap.josm.tools.Logging;

import jakarta.annotation.Nonnull;
import jakarta.json.Json;
import jakarta.json.JsonException;
import jakarta.annotation.Nullable;


/**
 * Another representation of a {@link Task} or a {@link Challenge}
 *
 * @param id                 The id
 * @param owner              The owner id
 * @param ownerName          The owner name
 * @param title              The title of the task
 * @param parentId           The id of the parent
 * @param parentName         The name of the parent
 * @param location           The location of the task
 * @param bounding           The bounds
 * @param blurb              The first line of the task instructions
 * @param modified           When the task was last modified
 * @param difficulty         The difficulty (only if a challenge)
 * @param type               The task type
 * @param status             The status of the task (not used on challenge points)
 * @param cooperativeWork    The cooperative work task as a string instead of object
 * @param mappedOn           The time the task was mapped
 * @param completedTimeSpent The amount of time from when the task was locked to when it was mapped
 * @param completedBy        Who completed the task
 * @param pointReview        Review information
 * @param priority           The priority of the task
 * @param bundleId           The bundle for this task
 * @param isBundlePrimary    If the bundle is the primary bundle
 * @param lockedBy           The MapRoulette user id holding a lock on the task or its bundle, if any
 * @param properties         The properties of the task's first feature that has any, empty if MapRoulette sent no
 *                           geometries
 */
public record ClusteredPoint(long id, long owner, @Nonnull String ownerName, String title, long parentId, @Nonnull String parentName,
                             @Nonnull Point location, @Nullable Object bounding, @Nonnull String blurb, @Nonnull Instant modified,
                             @Nullable Difficulty difficulty, int type, @Nullable TaskStatus status, @Nullable String cooperativeWork,
                             @Nullable Instant mappedOn, @Nullable Long completedTimeSpent, @Nullable BaseUser completedBy,
                             PointReview pointReview, int priority, @Nullable Long bundleId, @Nullable Boolean isBundlePrimary,
                             @Nullable Long lockedBy, @Nonnull Map<String, String> properties)
        implements TaskClusteredPoint {

    /**
     * Check if this is a multiple-choice task, which is answered in the Street Tally app, not from JOSM
     *
     * @return {@code true} for a multiple-choice task
     */
    public boolean isChoiceTask() {
        if (this.cooperativeWork == null || !this.cooperativeWork.contains("meta")) {
            return false;
        }
        try (var reader = Json.createReader(new StringReader(this.cooperativeWork))) {
            final var meta = reader.readObject().getJsonObject("meta");
            // Meta type 3, as in TaskParser
            return meta != null && meta.getInt("type", -1) == 3;
        } catch (JsonException | ClassCastException e) {
            Logging.trace(e);
            return false;
        }
    }

    /**
     * Get the task for this point.
     *
     * @return The task.
     * @throws IOException if there was a problem communicating with the server
     */
    public Task task() throws IOException {
        return TaskAPI.get(this.id());
    }

    @Override
    public BBox getBBox() {
        return this.location().getBBox();
    }
}
