// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.PrimitiveId;
import org.openstreetmap.josm.data.osm.SimplePrimitiveId;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.plugins.maproulette.api.model.Point;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;
import org.openstreetmap.josm.testutils.annotations.Main;
import org.openstreetmap.josm.testutils.annotations.Projection;

/**
 * Test class for {@link TaskData}
 */
@BasicPreferences
@Main
@Projection
class TaskDataTest {
    private static final PrimitiveId NODE = new SimplePrimitiveId(1, OsmPrimitiveType.NODE);
    private static final PrimitiveId WAY = new SimplePrimitiveId(2, OsmPrimitiveType.WAY);

    /**
     * Objects that are absent or incomplete must be downloaded; complete ones not
     */
    @Test
    void testMissing() {
        final var dataSet = new DataSet();
        final var node = new Node(LatLon.ZERO);
        node.setOsmId(1, 1);
        dataSet.addPrimitive(node);
        assertEquals(List.of(), TaskData.missing(List.of(NODE), dataSet));
        assertEquals(List.of(WAY), TaskData.missing(List.of(NODE, WAY), dataSet));
        dataSet.addPrimitive(new Way(2));
        assertEquals(List.of(WAY), TaskData.missing(List.of(NODE, WAY), dataSet), "An incomplete way is missing");
        assertEquals(List.of(NODE, WAY), TaskData.missing(List.of(NODE, WAY), null), "No edit layer");
    }

    /**
     * Objects already in the edit layer are selected without a download
     */
    @Test
    void testSelectLoaded() {
        final var dataSet = new DataSet();
        final var node = new Node(LatLon.ZERO);
        node.setOsmId(1, 1);
        dataSet.addPrimitive(node);
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(dataSet, "testSelectLoaded", null));
        TaskData.load(task(null), List.of(NODE));
        assertEquals(List.of(node), List.copyOf(dataSet.getSelected()));
    }

    /**
     * A task that names no objects downloads a small area around its location
     */
    @Test
    void testPointArea() {
        final var bounds = TaskData.bounds(task(new Point(40.5, -111.9)));
        assertNotNull(bounds);
        assertTrue(bounds.contains(new LatLon(40.5, -111.9)));
        assertEquals(0.002, bounds.getHeight(), 1e-9);
        assertEquals(0.002, bounds.getWidth(), 1e-9);
    }

    private static Task task(Point location) {
        return new Task(27, "Bench 27", null, null, 15318, null, location, new DataSet(), null, null, null, null, null,
                null, 0, null, null, null, false, null, null);
    }
}
