// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui.task.list;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.openstreetmap.josm.data.coor.ILatLon;
import org.openstreetmap.josm.gui.tagging.presets.TaggingPresetType;
import org.openstreetmap.josm.gui.tagging.presets.TaggingPresets;
import org.openstreetmap.josm.plugins.maproulette.api.enums.TaskStatus;
import org.openstreetmap.josm.plugins.maproulette.api.model.Challenge;
import org.openstreetmap.josm.plugins.maproulette.api.model.ClusteredPoint;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.plugins.maproulette.api.model.TaskClusteredPoint;
import org.openstreetmap.josm.plugins.maproulette.api_caching.ChallengeCache;
import org.openstreetmap.josm.plugins.maproulette.gui.MRGuiHelper;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedObjects;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * The text of the task list rows: a readable name, a details line and a status chip (#112)
 */
public final class TaskRows {
    /**
     * An OSM object id, as MapRoulette task names ({@code n123}) and GeoJSON {@code @id} properties ({@code node/123})
     * write it
     */
    private static final Pattern OSM_ID = Pattern.compile("^(?:(node|way|relation)/|([nwr]))(\\d+)$");

    /**
     * The status chip of a row: what the row's task needs from the mapper, in words, not colour alone
     */
    enum Chip {
        /** Started by this user */
        STARTED,
        /** Open to work on */
        OPEN,
        /** Another mapper is working on it */
        LOCKED,
        /** Finished: fixed, already fixed, not an issue, answered or validated */
        DONE,
        /** Skipped by a mapper */
        SKIPPED,
        /** A mapper could not complete it */
        CANT_COMPLETE,
        /** Deleted or disabled */
        UNAVAILABLE;

        /**
         * Get the text of the chip
         *
         * @return The text
         */
        String label() {
            return switch (this) {
            case STARTED -> tr("Started");
            case OPEN -> tr("Open");
            case LOCKED -> tr("Locked");
            case DONE -> tr("Done");
            case SKIPPED -> tr("Skipped");
            case CANT_COMPLETE -> tr("Can''t Complete");
            case UNAVAILABLE -> tr("Unavailable");
            };
        }
    }

    private TaskRows() {
        // Hide the constructor
    }

    /**
     * Get the challenge name of a task. This never blocks on the network.
     *
     * @param point The task
     * @return The challenge name, or "Unknown" while the challenge is not loaded
     */
    @Nonnull
    static String challengeName(@Nonnull TaskClusteredPoint point) {
        if (point instanceof ClusteredPoint clusteredPoint) {
            return clusteredPoint.parentName();
        }
        return ChallengeCache.cached(point.parentId()).map(Challenge::name).orElseGet(() -> tr("Unknown"));
    }

    /**
     * Get the MapRoulette name of a task, usually the OSM id of its object
     *
     * @param point The task
     * @return The name
     */
    @Nonnull
    static String taskName(@Nonnull TaskClusteredPoint point) {
        if (point instanceof ClusteredPoint clusteredPoint) {
            return clusteredPoint.title() == null ? "" : clusteredPoint.title();
        } else if (point instanceof Task task) {
            return task.name();
        }
        return "";
    }

    /**
     * Get the properties of a task's first feature that has any
     *
     * @param point The task
     * @return The properties, empty if unknown
     */
    @Nonnull
    static Map<String, String> properties(@Nonnull TaskClusteredPoint point) {
        if (point instanceof ClusteredPoint clusteredPoint) {
            return clusteredPoint.properties();
        } else if (point instanceof Task task) {
            return task.geometries().allPrimitives().stream().filter(p -> p.hasKeys()).findFirst().map(p -> p.getKeys())
                    .map(Map::copyOf).orElse(Collections.emptyMap());
        }
        return Collections.emptyMap();
    }

    /**
     * Get a readable name for a task: the JOSM preset name for its tags with the {@code name} or {@code ref} of its
     * object, such as {@code Bench "sue"}, else either of them, else its OSM object, else MapRoulette's task name. The
     * task list and the current task use it, so they always name a task the same way (#145).
     *
     * @param point The task
     * @return The name
     */
    @Nonnull
    public static String readableName(@Nonnull TaskClusteredPoint point) {
        final var properties = properties(point);
        String name = null;
        for (var key : new String[] { "name", "ref" }) {
            final var value = properties.get(key);
            if (value != null && !value.isBlank()) {
                name = value.strip();
                break;
            }
        }
        final var osmId = osmId(properties.getOrDefault("@id", taskName(point)));
        final var preset = presetName(properties, osmId == null ? null : osmId[0]);
        if (name != null) {
            // "Bench at Liberty Park" says what it is already
            return preset == null || name.toLowerCase(Locale.ROOT).contains(preset.toLowerCase(Locale.ROOT)) ? name
                    : tr("{0} \"{1}\"", preset, name);
        }
        if (preset != null) {
            return preset;
        }
        if (osmId != null) {
            return switch (osmId[0]) {
            case "node" -> tr("Node {0}", osmId[1]);
            case "way" -> tr("Way {0}", osmId[1]);
            default -> tr("Relation {0}", osmId[1]);
            };
        }
        final var taskName = taskName(point);
        return taskName.isBlank() ? tr("Task {0}", Long.toString(point.id())) : taskName;
    }

    /**
     * Parse an OSM object id
     *
     * @param text The text, like {@code n123} or {@code node/123}
     * @return The type ("node", "way" or "relation") and the number, or {@code null}
     */
    @Nullable
    static String[] osmId(@Nullable String text) {
        if (text == null) {
            return null;
        }
        final var matcher = OSM_ID.matcher(text.trim());
        if (!matcher.matches()) {
            return null;
        }
        final String type;
        if (matcher.group(1) != null) {
            type = matcher.group(1);
        } else {
            type = switch (matcher.group(2)) {
            case "n" -> "node";
            case "w" -> "way";
            default -> "relation";
            };
        }
        return new String[] { type, matcher.group(3) };
    }

    /**
     * Get the name of the JOSM preset that matches some tags
     *
     * @param properties The tags; keys starting with {@code @} are GeoJSON metadata and are left out
     * @param type       The OSM type ("node", "way" or "relation"), or {@code null} if unknown
     * @return The preset name, or {@code null}
     */
    @Nullable
    private static String presetName(Map<String, String> properties, @Nullable String type) {
        final var tags = new HashMap<String, String>(properties.size());
        properties.forEach((key, value) -> {
            if (!key.startsWith("@")) {
                tags.put(key, value);
            }
        });
        if (tags.isEmpty()) {
            return null;
        }
        final EnumSet<TaggingPresetType> types;
        if ("node".equals(type)) {
            types = EnumSet.of(TaggingPresetType.NODE);
        } else if ("way".equals(type)) {
            types = EnumSet.of(TaggingPresetType.WAY);
        } else if ("relation".equals(type)) {
            types = EnumSet.of(TaggingPresetType.RELATION);
        } else {
            types = null;
        }
        return TaggingPresets.getMatchingPresets(types, tags, true).stream().findFirst()
                .map(preset -> preset.getLocaleName()).orElse(null);
    }

    /**
     * Get the priority of a task in words
     *
     * @param priority MapRoulette's priority: 0 is high, 1 medium, 2 low
     * @return The priority in words
     */
    @Nonnull
    static String priority(int priority) {
        return switch (priority) {
        case 0 -> tr("High priority");
        case 1 -> tr("Medium priority");
        default -> tr("Low priority");
        };
    }

    /**
     * Format a distance
     *
     * @param meters The distance in meters
     * @return The distance, like "340 m" or "1.2 km"
     */
    @Nonnull
    static String distance(double meters) {
        // 995 m and more round to 1000 m: write it as 1.0 km
        if (meters < 995) {
            return tr("{0} m", Math.round(meters / 10) * 10);
        }
        return tr("{0} km", String.format("%.1f", meters / 1000));
    }

    /**
     * Get the details line of a row
     *
     * @param point The task
     * @param from  Where the distance is measured from, usually the map view's centre, or {@code null} for none
     * @return The details, like "Task 151 · High priority · 340 m"
     */
    @Nonnull
    static String details(@Nonnull TaskClusteredPoint point, @Nullable ILatLon from) {
        final var task = tr("Task {0}", Long.toString(point.id()));
        if (chip(point) == Chip.LOCKED) {
            return tr("Another mapper is working on it") + " · " + task;
        }
        if (isChoiceTask(point)) {
            return tr("Answer in the Street Tally app") + " · " + task;
        }
        final var details = new StringBuilder(task).append(" · ").append(priority(point.priority()));
        if (from != null && point.location() != null && from.isLatLonKnown()) {
            details.append(" · ").append(distance(point.location().greatCircleDistance(from)));
        }
        return details.toString();
    }

    /**
     * Check if a task is a multiple-choice task, answered in the Street Tally app
     *
     * @param point The task
     * @return {@code true} for a multiple-choice task
     */
    static boolean isChoiceTask(@Nonnull TaskClusteredPoint point) {
        if (point instanceof ClusteredPoint clusteredPoint) {
            return clusteredPoint.isChoiceTask();
        } else if (point instanceof Task task) {
            return task.isChoiceTask();
        }
        return false;
    }

    /**
     * Get the status chip of a row
     *
     * @param point The task
     * @return The chip
     */
    @Nonnull
    static Chip chip(@Nonnull TaskClusteredPoint point) {
        if (ModifiedObjects.getLockedTask(point.id()) != null) {
            return Chip.STARTED;
        }
        if (ModifiedObjects.isLockedByOther(point)) {
            return Chip.LOCKED;
        }
        return chip(status(point));
    }

    /**
     * Get the status of a task as this user knows it: a status being sent, then a status sent since the last load, then
     * the status the server gave
     *
     * @param point The task
     * @return The status, or {@code null} if the server gave none
     */
    @Nullable
    public static TaskStatus status(@Nonnull TaskClusteredPoint point) {
        final var modified = ModifiedObjects.getModifiedTask(point.id());
        if (modified != null) {
            return modified.status();
        }
        final var sent = ModifiedObjects.getSentStatus(point.id());
        return sent != null ? sent : point.status();
    }

    /**
     * Describe a status for the current task's header
     *
     * @param status The status
     * @return Such as "Done · Already fixed", or {@code null} for an open task
     */
    @Nullable
    public static String statusLine(@Nullable TaskStatus status) {
        final var statusChip = chip(status);
        return switch (statusChip) {
        case OPEN, STARTED, LOCKED -> null;
        case DONE, UNAVAILABLE -> statusChip.label() + " · " + MRGuiHelper.statusLabel(status);
        case SKIPPED, CANT_COMPLETE -> statusChip.label();
        };
    }

    /**
     * Get the chip of a status
     *
     * @param status The status
     * @return The chip
     */
    private static Chip chip(@Nullable TaskStatus status) {
        if (status == null) {
            return Chip.OPEN;
        }
        return switch (status) {
        case CREATED -> Chip.OPEN;
        case FIXED, FALSE_POSITIVE, ALREADY_FIXED, ANSWERED, VALIDATED -> Chip.DONE;
        case SKIPPED -> Chip.SKIPPED;
        case TOO_HARD -> Chip.CANT_COMPLETE;
        case DELETED, DISABLED -> Chip.UNAVAILABLE;
        };
    }

    /**
     * Get the text the filter field searches in: the name, the challenge, the task id and the properties
     *
     * @param point The task
     * @param name  The readable name of the task
     * @return The text
     */
    @Nonnull
    static String searchText(@Nonnull TaskClusteredPoint point, @Nonnull String name) {
        final var text = new StringBuilder(name).append('\n').append(challengeName(point)).append('\n')
                .append(point.id()).append('\n').append(taskName(point));
        properties(point).values().forEach(value -> text.append('\n').append(value));
        return text.toString();
    }
}
