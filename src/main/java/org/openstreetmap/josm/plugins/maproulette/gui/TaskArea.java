// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.openstreetmap.josm.data.osm.BBox;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.plugins.maproulette.data.TaskPrimitives;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * Which edited objects belong to a task: its own objects, and objects near it (#149). "Near" is within
 * {@value #NEAR_METRES} m of the bounding box of the task's geometry, or of its location when it has no geometry.
 */
public final class TaskArea {
    /**
     * How close an object must be to count as near the task, in metres
     */
    public static final double NEAR_METRES = 50;
    /**
     * Metres per degree of latitude
     */
    private static final double METRES_PER_DEGREE = 111_320;

    private TaskArea() {
        // Hide the constructor
    }

    /**
     * Check if an edited object concerns a task
     *
     * @param task      The task
     * @param primitive The edited object
     * @return {@code true} if it is one of the task's objects, or near the task
     */
    public static boolean concerns(@Nonnull Task task, @Nonnull OsmPrimitive primitive) {
        if (TaskPrimitives.cachedPrimitiveIds(task).map(ids -> ids.contains(primitive.getPrimitiveId()))
                .orElse(false)) {
            return true;
        }
        final var area = area(task);
        return area != null && near(area, primitive, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    /**
     * Get the area that counts as near a task
     *
     * @param task The task
     * @return The bounding box of the task's geometry or location, grown by {@value #NEAR_METRES} m, or {@code null}
     *         if the task has neither
     */
    @Nullable
    static BBox area(@Nonnull Task task) {
        BBox box = null;
        for (var geometry : task.geometries().allNonDeletedPrimitives()) {
            final var geometryBox = geometry.getBBox();
            if (geometryBox.isValid()) {
                if (box == null) {
                    box = new BBox(geometryBox);
                } else {
                    box.add(geometryBox);
                }
            }
        }
        if (box == null && task.location() != null) {
            box = new BBox(task.location().getBBox());
        }
        if (box == null || !box.isValid()) {
            return null;
        }
        // Metres to degrees: a degree of longitude shrinks with the cosine of the latitude
        final var latitude = Math.toRadians(box.getCenter().lat());
        final var dLat = NEAR_METRES / METRES_PER_DEGREE;
        final var dLon = NEAR_METRES / (METRES_PER_DEGREE * Math.max(Math.cos(latitude), 0.01));
        return new BBox(box.getTopLeftLon() - dLon, box.getBottomRightLat() - dLat, box.getBottomRightLon() + dLon,
                box.getTopLeftLat() + dLat);
    }

    /**
     * Check if an object is in the area. A relation is near only if one of its loaded members is.
     *
     * @param area      The area
     * @param primitive The object
     * @param seen      The relations already looked at, so a relation that contains itself ends
     * @return {@code true} if it is near
     */
    private static boolean near(BBox area, OsmPrimitive primitive, Set<OsmPrimitive> seen) {
        if (primitive instanceof Relation relation) {
            if (!seen.add(relation)) {
                return false;
            }
            final List<OsmPrimitive> members = relation.getMembers().stream().map(RelationMember::getMember)
                    .filter(member -> !member.isIncomplete()).toList();
            return members.stream().anyMatch(member -> near(area, member, seen));
        }
        if (primitive instanceof Node node && !node.isLatLonKnown()) {
            return false;
        }
        final var box = primitive.getBBox();
        return box.isValid() && area.intersects(box);
    }
}
