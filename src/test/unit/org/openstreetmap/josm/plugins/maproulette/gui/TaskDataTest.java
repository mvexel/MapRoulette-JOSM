// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.DataSource;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.gpx.GpxData;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.PrimitiveId;
import org.openstreetmap.josm.data.osm.SimplePrimitiveId;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.GpxLayer;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.io.OsmReader;
import org.openstreetmap.josm.plugins.maproulette.api.model.Challenge;
import org.openstreetmap.josm.plugins.maproulette.api.model.Point;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.plugins.maproulette.api_caching.ChallengeCache;
import org.openstreetmap.josm.plugins.maproulette.gui.task.current.CurrentTaskPanel;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;
import org.openstreetmap.josm.testutils.annotations.Main;
import org.openstreetmap.josm.testutils.annotations.Projection;

import mockit.Invocation;
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
     * After a restart, the task's object is selected in the layer JOSM restored from its autosave, also when another
     * layer is active (#140)
     *
     * @throws Exception if the test fails
     */
    @Test
    void testSelectInRestoredAutosave() throws Exception {
        // What JOSM's autosave holds for an edited task object; the restore reads it as OsmImporter does
        final var xml = "<osm version='0.6' upload='true' generator='JOSM'><node id='4365677669' action='modify'"
                + " version='5' lat='40.7608' lon='-111.8848'><tag k='amenity' v='bench'/>"
                + "<tag k='backrest' v='yes'/></node></osm>";
        final var restored = OsmReader.parseDataSet(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),
                null);
        final var layer = new OsmDataLayer(restored, "Data Layer 1", null);
        MainApplication.getLayerManager().addLayer(layer);
        // Load tasks here adds the task layer on top
        MainApplication.getLayerManager().addLayer(new GpxLayer(new GpxData(), "MapRoulette tasks"));

        final var geometries = new DataSet();
        final var feature = new Node(new LatLon(40.7608, -111.8848));
        feature.put("@id", "node/4365677669");
        feature.put("amenity", "bench");
        geometries.addPrimitive(feature);
        final var task = new Task(235, "235", null, null, 10, null, null, geometries, null, null, null, null, null,
                null, 0, null, null, null, false, null, null);
        new MockUp<ChallengeCache>() {
            @Mock
            Challenge challenge(long id) throws IOException {
                throw new IOException("No challenge in this test");
            }
        };

        GuiHelper.runInEDTAndWait(() -> TaskData.loadAndSelect(task));
        MainApplication.worker.submit(() -> {
            /* Sync worker */ }).get(10, TimeUnit.SECONDS);
        GuiHelper.runInEDTAndWait(() -> {
            /* Sync EDT */ });
        assertEquals(List.of(restored.getPrimitiveById(4365677669L, OsmPrimitiveType.NODE)),
                List.copyOf(restored.getSelected()));
    }

    /**
     * If none of the task's objects could be downloaded (404 on the OSM server), the caller is told (#143)
     *
     * @throws Exception if the test fails
     */
    @Test
    void testNothingDownloaded() throws Exception {
        final var noted = new CopyOnWriteArrayList<Long>();
        new MockUp<CurrentTaskPanel>() {
            @Mock
            void notDownloaded(long taskId) {
                noted.add(taskId);
            }
        };
        final var notFound = new CopyOnWriteArrayList<PrimitiveId>();
        assertEquals(1, loadWithDownload(Server.NOT_FOUND, notFound));
        assertEquals(List.of(27L), noted, "The panel says the download brought nothing (#145)");
        assertEquals(List.of(NODE), notFound, "The panel names the objects the server doesn't have (#116)");
    }

    /**
     * If the OSM server can't be reached, the panel says the objects could not be downloaded, but the caller is not
     * told that they don't exist
     */
    @Test
    void testServerUnreachable() throws Exception {
        final var noted = new CopyOnWriteArrayList<Long>();
        new MockUp<CurrentTaskPanel>() {
            @Mock
            void notDownloaded(long taskId) {
                noted.add(taskId);
            }
        };
        assertEquals(0, loadWithDownload(Server.UNREACHABLE));
        assertEquals(List.of(27L), noted);
    }

    /**
     * If the task's objects were downloaded, the caller is not told
     *
     * @throws Exception if the test fails
     */
    @Test
    void testDownloaded() throws Exception {
        final var noted = new CopyOnWriteArrayList<Long>();
        new MockUp<CurrentTaskPanel>() {
            @Mock
            void notDownloaded(long taskId) {
                noted.add(taskId);
            }
        };
        assertEquals(0, loadWithDownload(Server.FOUND));
        assertEquals(List.of(), noted);
    }

    /**
     * A task that names no objects zooms to its location, even when its area is loaded already (#145)
     */
    @Test
    void testZoomToTaskWithoutObjects() {
        final var task = task(new Point(40.5, -111.9));
        final var dataSet = new DataSet();
        dataSet.addDataSource(new DataSource(TaskData.bounds(task), "test"));
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(dataSet, "testZoomToTask", null));
        GuiHelper.runInEDTAndWait(() -> TaskData.load(task, List.of()));
        final var centre = MainApplication.getMap().mapView.getRealBounds().getCenter();
        assertEquals(40.5, centre.lat(), 0.001);
        assertEquals(-111.9, centre.lon(), 0.001);
    }

    /**
     * Load a task's node that is not in the edit layer yet
     *
     * @param server What the OSM server answers
     * @return How often the caller was told that nothing could be downloaded
     */
    private static int loadWithDownload(Server server) throws Exception {
        return loadWithDownload(server, new CopyOnWriteArrayList<>());
    }

    /**
     * Load a task's node that is not in the edit layer yet
     *
     * @param server   What the OSM server answers
     * @param notFound Gets the objects the server doesn't have, as the caller is told
     * @return How often the caller was told that nothing could be downloaded
     */
    private static int loadWithDownload(Server server, List<PrimitiveId> notFound) throws Exception {
        final var dataSet = new DataSet();
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(dataSet, "loadWithDownload", null));
        new MockUp<TaskData.ObjectsDownload>() {
            @Mock
            void realRun(Invocation invocation) throws ReflectiveOperationException {
                final TaskData.ObjectsDownload download = invocation.getInvokedInstance();
                if (server == Server.FOUND) {
                    final var node = new Node(LatLon.ZERO);
                    node.setOsmId(1, 1);
                    final var tmpLayer = TaskData.ObjectsDownload.class.getDeclaredField("tmpLayer");
                    tmpLayer.setAccessible(true);
                    ((OsmDataLayer) tmpLayer.get(download)).data.addPrimitive(node);
                } else if (server == Server.NOT_FOUND) {
                    // The OSM server answers 404
                    final var missing = TaskData.ObjectsDownload.class.getDeclaredField("notFound");
                    missing.setAccessible(true);
                    missing.set(download, Set.of(NODE));
                }
            }
        };
        final var told = new AtomicInteger();
        GuiHelper.runInEDTAndWait(() -> TaskData.load(task(null), List.of(NODE), ids -> {
            told.incrementAndGet();
            notFound.addAll(ids);
        }));
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

    /**
     * What the OSM server answers for the task's node
     */
    private enum Server {
        /** It has the node */
        FOUND,
        /** 404 */
        NOT_FOUND,
        /** No answer: the download fails, and JOSM shows the error */
        UNREACHABLE
    }

    private static Task task(Point location) {
        return new Task(27, "Bench 27", null, null, 15318, null, location, new DataSet(), null, null, null, null, null,
                null, 0, null, null, null, false, null, null);
    }
}
