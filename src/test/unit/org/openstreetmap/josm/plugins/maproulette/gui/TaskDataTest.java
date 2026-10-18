// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.PrimitiveId;
import org.openstreetmap.josm.data.osm.SimplePrimitiveId;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.io.DownloadPrimitivesWithReferrersTask;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.plugins.maproulette.api.model.Point;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;
import org.openstreetmap.josm.testutils.annotations.Main;
import org.openstreetmap.josm.testutils.annotations.Projection;

import mockit.Mock;
import mockit.MockUp;

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
     * If none of the task's objects could be downloaded (404 on the OSM server), the caller is told (#143)
     *
     * @throws Exception if the test fails
     */
    @Test
    void testNothingDownloaded() throws Exception {
        assertEquals(1, loadWithDownload(false));
    }

    /**
     * If the task's objects were downloaded, the caller is not told
     *
     * @throws Exception if the test fails
     */
    @Test
    void testDownloaded() throws Exception {
        assertEquals(0, loadWithDownload(true));
    }

    /**
     * Load a task's node that is not in the edit layer yet
     *
     * @param found {@code true} if the OSM server has the node
     * @return How often the caller was told that nothing could be downloaded
     */
    private static int loadWithDownload(boolean found) throws Exception {
        final var dataSet = new DataSet();
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(dataSet, "loadWithDownload", null));
        new MockUp<DownloadPrimitivesWithReferrersTask>() {
            @Mock
            void realRun() {
                if (found) {
                    final var node = new Node(LatLon.ZERO);
                    node.setOsmId(1, 1);
                    dataSet.addPrimitive(node);
                }
            }

            @Mock
            void finish() {
                // The real one reports the objects it could not download
            }
        };
        final var told = new AtomicInteger();
        GuiHelper.runInEDTAndWait(() -> TaskData.load(task(null), List.of(NODE), told::incrementAndGet));
        MainApplication.worker.submit(() -> {
            /* Sync worker */ }).get(10, TimeUnit.SECONDS);
        GuiHelper.runInEDTAndWait(() -> {
            /* Sync EDT */ });
        return told.get();
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
