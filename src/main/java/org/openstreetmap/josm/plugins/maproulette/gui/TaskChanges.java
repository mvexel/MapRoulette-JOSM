// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui;

import static org.openstreetmap.josm.tools.I18n.tr;
import static org.openstreetmap.josm.tools.I18n.trn;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.DefaultNameFormatter;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.NodeData;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.PrimitiveData;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationData;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.osm.WayData;
import org.openstreetmap.josm.gui.tagging.presets.TaggingPresetType;
import org.openstreetmap.josm.gui.tagging.presets.TaggingPresets;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.plugins.maproulette.data.TaskPrimitives;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * What the user changed for a task, to show before it is finished (#148): the tag changes on the task's own objects
 * since Start, the objects created near it, and a hint when the instructions name a tag that didn't change
 */
public final class TaskChanges {
    /**
     * At most this many lines are listed
     */
    static final int MAX_LINES = 6;
    /**
     * An OSM key, as instructions write it: {@code key=value}, {@code key=*} or {@code `key`}
     */
    private static final Pattern INSTRUCTION_KEY = Pattern
            .compile("(?<![\\w:])([a-z][a-z0-9_:]*)=(?:\\*|[^\\s=])|`([a-z][a-z0-9_:]*)`");
    /**
     * Mustache placeholders, filled from the task's properties
     */
    private static final Pattern MUSTACHE = Pattern.compile("\\{\\{.*?}}");
    /**
     * Links, whose query strings can look like tags
     */
    private static final Pattern URL = Pattern.compile("\\w+://\\S+");

    /**
     * What changed for a task
     *
     * @param lines   The changes, one per line, at most {@value #MAX_LINES} and a line saying how many more
     * @param warning A hint that the instructions mention a tag that didn't change, or {@code null}
     */
    public record Summary(@Nonnull List<String> lines, @Nullable String warning) {
        /**
         * Nothing to show
         */
        static final Summary NONE = new Summary(List.of(), null);
    }

    private TaskChanges() {
        // Hide the constructor
    }

    /**
     * Describe what changed for a task. Tag-fix (cooperative) tasks get nothing: their change is already exact.
     *
     * @param task    The task
     * @param dataSet The edit data set, or {@code null}
     * @return The changes
     */
    @Nonnull
    public static Summary of(@Nonnull Task task, @Nullable DataSet dataSet) {
        if (dataSet == null || task.cooperativeWork() != null) {
            return Summary.NONE;
        }
        final var lines = new ArrayList<String>();
        final var changedKeys = new TreeSet<String>();
        final var unchanged = new ArrayList<OsmPrimitive>();
        final var ids = TaskPrimitives.cachedPrimitiveIds(task).orElse(List.of());
        for (var id : ids) {
            final var primitive = dataSet.getPrimitiveById(id);
            final var base = primitive == null ? null : TaskEdits.baseline(primitive);
            if (base == null) {
                continue;
            }
            final var parts = changes(base, primitive, changedKeys);
            // Named as it was, so removing the tag that named it doesn't rename it
            final var name = name(primitive, base.getKeys());
            if (!parts.isEmpty()) {
                lines.add(tr("{0}: {1}", name, String.join(" · ", parts)));
            } else if (primitive.isModified()) {
                // The edit is older than Start, so it is part of the baseline
                lines.add(tr("{0}: changed before you started, not uploaded yet", name));
            } else {
                unchanged.add(primitive);
            }
        }
        for (var created : TaskEdits.created()) {
            if (created.getDataSet() == dataSet && created.hasKeys() && TaskArea.concerns(task, created)) {
                changedKeys.addAll(created.keySet());
                lines.add(tr("New: {0} ({1})", name(created), new TreeMap<>(created.getKeys()).entrySet().stream()
                        .map(tag -> tag.getKey() + '=' + tag.getValue()).collect(Collectors.joining(", "))));
            }
        }
        if (lines.isEmpty() && !unchanged.isEmpty()) {
            lines.add(unchanged.size() == 1 ? tr("No changes to {0}.", name(unchanged.get(0)))
                    : trn("No changes to the task''s object.", "No changes to the task''s {0} objects.",
                            unchanged.size(), unchanged.size()));
        }
        if (lines.size() > MAX_LINES) {
            final var more = lines.size() - MAX_LINES;
            lines.subList(MAX_LINES, lines.size()).clear();
            lines.add(trn("and {0} more change", "and {0} more changes", more, more));
        }
        final var mentioned = instructionKeys(MRGuiHelper.rawInstruction(task));
        final String warning;
        if (mentioned.isEmpty() || mentioned.stream().anyMatch(changedKeys::contains)) {
            warning = null;
        } else {
            warning = tr("The instructions mention {0}, which you didn''t change.", String.join(", ", mentioned));
        }
        return new Summary(List.copyOf(lines), warning);
    }

    /**
     * Describe the changes of an object since its baseline
     *
     * @param base        The object as it was
     * @param primitive   The object now
     * @param changedKeys Gets the keys that changed
     * @return The changes, such as "+ backrest=yes", "~ material=wood → metal", "− seats=3" and "moved"
     */
    static List<String> changes(PrimitiveData base, OsmPrimitive primitive, Set<String> changedKeys) {
        final var parts = new ArrayList<String>();
        if (primitive.isDeleted()) {
            parts.add(tr("deleted"));
            return parts;
        }
        final Map<String, String> before = new TreeMap<>(base.getKeys());
        final Map<String, String> after = new TreeMap<>(primitive.getKeys());
        final var keys = new TreeSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (var key : keys) {
            final var old = before.get(key);
            final var now = after.get(key);
            if (old == null) {
                parts.add("+ " + key + '=' + now);
            } else if (now == null) {
                parts.add("\u2212 " + key + '=' + old);
            } else if (!old.equals(now)) {
                parts.add("~ " + key + '=' + old + " \u2192 " + now);
            } else {
                continue;
            }
            changedKeys.add(key);
        }
        if (primitive instanceof Node node && base instanceof NodeData nodeData
                && !Objects.equals(node.getCoor(), nodeData.getCoor())) {
            parts.add(tr("moved"));
        } else if (primitive instanceof Way way && base instanceof WayData wayData
                && !way.getNodes().stream().map(OsmPrimitive::getUniqueId).toList().equals(wayData.getNodeIds())) {
            parts.add(tr("nodes changed"));
        } else if (primitive instanceof Relation relation && base instanceof RelationData relationData
                && !members(relation).equals(relationData.getMembers().stream()
                        .map(member -> member.getRole() + ' ' + member.getUniqueId()).toList())) {
            parts.add(tr("members changed"));
        }
        return parts;
    }

    private static List<String> members(Relation relation) {
        return relation.getMembers().stream().map(member -> member.getRole() + ' ' + member.getUniqueId()).toList();
    }

    /**
     * Get the keys an instruction names. Instructions are free text, so this guesses, and a hint is all it is good for.
     *
     * @param instruction The instruction, or {@code null}
     * @return The keys, in the order they are named
     */
    @Nonnull
    static Collection<String> instructionKeys(@Nullable String instruction) {
        if (instruction == null) {
            return List.of();
        }
        final var text = URL.matcher(MUSTACHE.matcher(instruction).replaceAll(" ")).replaceAll(" ");
        final var keys = new LinkedHashSet<String>();
        final var matcher = INSTRUCTION_KEY.matcher(text);
        while (matcher.find()) {
            keys.add(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
        }
        return keys;
    }

    /**
     * Get a readable name for an object: its preset and its name, such as {@code Bench "sue"}
     *
     * @param primitive The object
     * @return The name
     */
    @Nonnull
    static String name(@Nonnull OsmPrimitive primitive) {
        return name(primitive, primitive.getKeys());
    }

    /**
     * Get a readable name for an object from some of its tags
     *
     * @param primitive The object
     * @param tags      The tags to name it by
     * @return The name
     */
    @Nonnull
    static String name(@Nonnull OsmPrimitive primitive, @Nonnull Map<String, String> tags) {
        final var preset = TaggingPresets
                .getMatchingPresets(EnumSet.of(TaggingPresetType.forPrimitive(primitive)), tags, false).stream()
                .findFirst().map(match -> match.getLocaleName()).orElse(null);
        final var name = tags.get("name") != null ? tags.get("name") : tags.get("ref");
        if (preset == null && tags.equals(primitive.getKeys())) {
            return DefaultNameFormatter.getInstance().format(primitive);
        } else if (preset == null) {
            // The tags are older than the object's: JOSM's formatter would name it by its current tags
            return name != null ? name : switch (primitive.getType()) {
            case NODE -> tr("Node {0}", Long.toString(primitive.getUniqueId()));
            case WAY -> tr("Way {0}", Long.toString(primitive.getUniqueId()));
            default -> tr("Relation {0}", Long.toString(primitive.getUniqueId()));
            };
        } else if (name == null || name.isBlank()) {
            return preset;
        }
        return tr("{0} \"{1}\"", preset, name);
    }
}
