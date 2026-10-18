// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.maproulette.api.model.Point;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;

/**
 * Test class for {@link TaskArea}
 */
class TaskAreaTest {
    /** Salt Lake City, where a degree of longitude is about 84 km */
    private static final double LAT = 40.76;
    private static final double LON = -111.89;

    private static Task task(long id, DataSet geometries, Point location) {
        return new Task(id, "Bench " + id, null, null, 10, null, location, geometries, null, null, null, null, null,
                null, 0, null, null, null, false, null, null);
    }

    /** A task whose geometry is one bench */
    private static Task benchTask() {
        final var geometries = new DataSet();
        final var bench = new Node(new LatLon(LAT, LON));
        bench.put("amenity", "bench");
        geometries.addPrimitive(bench);
        return task(900_149, geometries, null);
    }

    /** A node about the given distance north and east of the bench, in metres */
    private static Node node(double north, double east) {
        return new Node(new LatLon(LAT + north / 111_320, LON + east / (111_320 * Math.cos(Math.toRadians(LAT)))));
    }

    @Test
    void testNearNodes() {
        final var task = benchTask();
        assertTrue(TaskArea.concerns(task, node(30, 0)));
        assertTrue(TaskArea.concerns(task, node(0, 45)), "Metres east use the cosine of the latitude");
        assertFalse(TaskArea.concerns(task, node(80, 0)));
        assertFalse(TaskArea.concerns(task, node(0, 60)));
    }

    @Test
    void testWayAcrossTheTask() {
        final var task = benchTask();
        // Both ends are far, but the way passes the bench
        final var way = new Way();
        way.setNodes(List.of(node(0, -200), node(0, 200)));
        assertTrue(TaskArea.concerns(task, way));
        final var farWay = new Way();
        farWay.setNodes(List.of(node(100, -200), node(100, 200)));
        assertFalse(TaskArea.concerns(task, farWay));
    }

    @Test
    void testRelationNearOnlyThroughLoadedMembers() {
        final var task = benchTask();
        final var relation = new Relation();
        relation.addMember(new RelationMember("", node(500, 0)));
        assertFalse(TaskArea.concerns(task, relation));
        relation.addMember(new RelationMember("", relation));
        assertFalse(TaskArea.concerns(task, relation), "A relation that contains itself ends");
        relation.addMember(new RelationMember("", node(10, 10)));
        assertTrue(TaskArea.concerns(task, relation));
    }

    @Test
    void testLocationWithoutGeometry() {
        final var task = task(900_150, new DataSet(), new Point(LAT, LON));
        assertTrue(TaskArea.concerns(task, node(30, 0)));
        assertFalse(TaskArea.concerns(task, node(80, 0)));
        assertNull(TaskArea.area(task(900_151, new DataSet(), null)), "Nothing is near a task with no place");
        assertFalse(TaskArea.concerns(task(900_151, new DataSet(), null), node(0, 0)));
    }
}
