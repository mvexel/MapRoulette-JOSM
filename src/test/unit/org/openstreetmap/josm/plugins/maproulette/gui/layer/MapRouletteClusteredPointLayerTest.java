// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui.layer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapViewState;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.gui.widgets.FilterField;
import org.openstreetmap.josm.plugins.maproulette.actions.IgnoreAction;
import org.openstreetmap.josm.plugins.maproulette.api.enums.TaskStatus;
import org.openstreetmap.josm.plugins.maproulette.api.model.ClusteredPoint;
import org.openstreetmap.josm.plugins.maproulette.api.model.Identifier;
import org.openstreetmap.josm.plugins.maproulette.api.model.Point;
import org.openstreetmap.josm.plugins.maproulette.api.model.PointReview;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedObjects;
import org.openstreetmap.josm.plugins.maproulette.gui.MapRouletteDialog;
import org.openstreetmap.josm.plugins.maproulette.gui.TaskListPanelTest;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;
import org.openstreetmap.josm.testutils.annotations.HTTP;
import org.openstreetmap.josm.testutils.annotations.Main;
import org.openstreetmap.josm.testutils.annotations.Projection;

import mockit.Mock;
import mockit.MockUp;

/**
 * Test class for {@link MapRouletteClusteredPointLayer}
 */
@BasicPreferences
@HTTP
@Main
@MapRouletteConfig
@Projection
class MapRouletteClusteredPointLayerTest {
    /**
     * Make certain that the selection code works properly when a task is hidden
     */
    @Test
    void testShiftSelection() throws ExecutionException, InterruptedException, TimeoutException {
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(new DataSet(), "testShiftSelection", null));
        final var bounds = new Bounds(36.0770518, -119.1139411, 36.0839216, -119.0992458);
        new MockUp<MapViewState.MapViewRectangle>() {
            @Mock
            public Bounds getLatLonBoundsBox() {
                return bounds;
            }
        };
        final var dialog = new MapRouletteDialog();
        final var panel = dialog.taskList();
        try {
            MainApplication.getMap().addToggleDialog(dialog);
            TaskListPanelTest.getDownloadAction(panel).actionPerformed(null);
            sync();
            final var layers = MainApplication.getLayerManager().getLayersOfType(MapRouletteClusteredPointLayer.class);
            assertEquals(1, layers.size());
            final var layer = layers.get(0);
            assertTrue(panel.getSelected().isEmpty());
            // Check and make certain that "simple" click events work properly
            layer.mouseClicked(generateMouseEvent(false, 36.0778797, -119.1075725));
            assertEquals(1, panel.getSelected().size());
            assertEquals(133361784L, panel.getSelected().iterator().next().id());
            layer.mouseClicked(generateMouseEvent(false, 36.080299, -119.1074128));
            assertEquals(1, panel.getSelected().size());
            assertEquals(133361785L, panel.getSelected().iterator().next().id());
            // Now what happens with shift events?
            layer.mouseClicked(generateMouseEvent(true, 36.0778797, -119.1075725));
            assertEquals(2, panel.getSelected().size());
            assertTrue(panel.getSelected().stream().mapToLong(Identifier::id)
                    .allMatch(id -> id == 133361784L || id == 133361785L));
            assertEquals(2, panel.getSelected().stream().mapToLong(Identifier::id).distinct().count());
            // Now, let us hide the challenge for the objects we have selected
            TaskListPanelTest.fireIgnoreAction(panel, IgnoreAction.IgnoreType.IGNORE_CHALLENGE);
            assertEquals(0, panel.getSelected().size());
            layer.mouseClicked(generateMouseEvent(true, 36.0821182637293, -119.104558450006));
            assertEquals(0, panel.getSelected().size());
        } finally {
            MainApplication.getMap().removeToggleDialog(dialog);
        }
    }

    /**
     * Non-regression test for <a href="https://josm.openstreetmap.de/ticket/24415">#24415</a>
     *
     * This occurs when the user clicks on a hidden task (from the filter)
     */
    @Test
    void testNonRegression24415() throws InterruptedException, ExecutionException, TimeoutException {
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(new DataSet(), "testShiftSelection", null));
        final var bounds = new Bounds(36.0770518, -119.1139411, 36.0839216, -119.0992458);
        new MockUp<MapViewState.MapViewRectangle>() {
            @Mock
            public Bounds getLatLonBoundsBox() {
                return bounds;
            }
        };
        final var dialog = new MapRouletteDialog();
        final var panel = dialog.taskList();
        try {
            MainApplication.getMap().addToggleDialog(dialog);
            TaskListPanelTest.getDownloadAction(panel).actionPerformed(null);
            sync();
            final var layers = MainApplication.getLayerManager().getLayersOfType(MapRouletteClusteredPointLayer.class);
            assertEquals(1, layers.size());
            final var layer = layers.get(0);
            assertTrue(panel.getSelected().isEmpty());
            // Check and make certain that "simple" click events work properly
            layer.mouseClicked(generateMouseEvent(false, 36.0778797, -119.1075725));
            sync();
            assertEquals(1, panel.getSelected().size());
            assertEquals(133361784L, panel.getSelected().iterator().next().id());
            layer.mouseClicked(generateMouseEvent(false, 36.080299, -119.1074128));
            assertEquals(1, panel.getSelected().size());
            assertEquals(133361785L, panel.getSelected().iterator().next().id());
            // Now what happens when we filter the images
            final FilterField filter = TaskListPanelTest.getFilterField(panel);
            GuiHelper.runInEDTAndWait(() -> filter.setText("foobar12345678912345678"));
            sync();
            assertDoesNotThrow(() -> layer.mouseClicked(generateMouseEvent(false, 36.0778797, -119.1075725)));
            assertEquals(0, panel.getSelected().size());
        } finally {
            MainApplication.getMap().removeToggleDialog(dialog);
        }
    }

    /**
     * The current task window keeps the locked task when the list filter hides its row
     */
    @Test
    void testFilterKeepsLockedTask() throws InterruptedException, ExecutionException, TimeoutException {
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(new DataSet(), "testFilterKeepsLockedTask", null));
        final var bounds = new Bounds(36.0770518, -119.1139411, 36.0839216, -119.0992458);
        new MockUp<MapViewState.MapViewRectangle>() {
            @Mock
            public Bounds getLatLonBoundsBox() {
                return bounds;
            }
        };
        final var locked = new Task(133361784L, "", null, null, 15318, null, null, new DataSet(), null, null, null,
                null, null, null, 0, null, null, null, false, null, null);
        final var dialog = new MapRouletteDialog();
        final var panel = dialog.taskList();
        try {
            MainApplication.getMap().addToggleDialog(dialog);
            TaskListPanelTest.getDownloadAction(panel).actionPerformed(null);
            sync();
            final var layer = MainApplication.getLayerManager().getLayersOfType(MapRouletteClusteredPointLayer.class)
                    .get(0);
            ModifiedObjects.addLockedTask(locked);
            layer.mouseClicked(generateMouseEvent(false, 36.0778797, -119.1075725));
            sync();
            final var currentTaskPanel = dialog.currentTask();
            assertTrue(currentTaskPanel.showsLockedTask());
            final FilterField filter = TaskListPanelTest.getFilterField(panel);
            // Change Swing state on the EDT: the lock listeners update the list there at the same time
            GuiHelper.runInEDTAndWait(() -> filter.setText("foobar12345678912345678"));
            sync();
            assertEquals(0, panel.getSelected().size());
            assertTrue(currentTaskPanel.showsLockedTask(), "The filter must not drop the locked task");
            // Once released, the next selection change clears the window
            ModifiedObjects.removeLockedTask(locked);
            GuiHelper.runInEDTAndWait(() -> filter.setText(""));
            sync();
            layer.mouseClicked(generateMouseEvent(false, 36.080299, -119.1074128));
            sync();
            assertFalse(currentTaskPanel.showsLockedTask());
        } finally {
            ModifiedObjects.removeLockedTask(locked);
            MainApplication.getMap().removeToggleDialog(dialog);
        }
    }

    /**
     * Non-regression test for <a href="https://josm.openstreetmap.de/ticket/24760">#24760</a>
     * @param box The box to test
     */
    @ParameterizedTest
    @ValueSource(strings = { "121.0923654/14.57171/121.1008573/14.5774521" })
    void testNonRegression24760(String box) throws ExecutionException, InterruptedException, TimeoutException {
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(new DataSet(), "testShiftSelection", null));
        double[] boxDouble = Arrays.stream(box.split("/", 4)).mapToDouble(Double::parseDouble).toArray();
        final var bounds = new Bounds(boxDouble[1], boxDouble[0], boxDouble[3], boxDouble[2]);
        new MockUp<MapViewState.MapViewRectangle>() {
            @Mock
            public Bounds getLatLonBoundsBox() {
                return bounds;
            }
        };
        final var dialog = new MapRouletteDialog();
        final var panel = dialog.taskList();
        try {
            MainApplication.getMap().addToggleDialog(dialog);
            TaskListPanelTest.getDownloadAction(panel).actionPerformed(null);
            sync();
            final var layers = MainApplication.getLayerManager().getLayersOfType(MapRouletteClusteredPointLayer.class);
            assertEquals(1, layers.size());
            final var layer = layers.get(0);
            assertTrue(panel.getSelected().isEmpty());
            // Check and make certain that "simple" click events work properly
            final var task = layer.getTasks().iterator().next();
            layer.mouseClicked(generateMouseEvent(false, task.location().lat(), task.location().lon()));
            sync();
            assertEquals(1, panel.getSelected().size());
            assertEquals(task.id(), panel.getSelected().iterator().next().id());
        } finally {
            MainApplication.getMap().removeToggleDialog(dialog);
        }
    }

    /**
     * Painting must not fail when the task list dialog is not in the map frame
     */
    @Test
    void testPaintWithoutTaskListPanel() throws ExecutionException, InterruptedException, TimeoutException {
        final var layer = downloadTestLayer();
        final var mv = MainApplication.getMap().mapView;
        final var image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
        final var graphics = image.createGraphics();
        try {
            assertDoesNotThrow(() -> layer.paint(graphics, mv, mv.getRealBounds()));
        } finally {
            graphics.dispose();
        }
    }

    /**
     * Download tasks into a new layer using a task list panel, then remove the panel from the map frame
     *
     * @return The downloaded layer
     */
    private static MapRouletteClusteredPointLayer downloadTestLayer()
            throws ExecutionException, InterruptedException, TimeoutException {
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(new DataSet(), "downloadTestLayer", null));
        final var bounds = new Bounds(36.0770518, -119.1139411, 36.0839216, -119.0992458);
        new MockUp<MapViewState.MapViewRectangle>() {
            @Mock
            public Bounds getLatLonBoundsBox() {
                return bounds;
            }
        };
        final var dialog = new MapRouletteDialog();
        final var panel = dialog.taskList();
        MainApplication.getMap().addToggleDialog(dialog);
        TaskListPanelTest.getDownloadAction(panel).actionPerformed(null);
        sync();
        MainApplication.getMap().removeToggleDialog(dialog);
        final var layers = MainApplication.getLayerManager().getLayersOfType(MapRouletteClusteredPointLayer.class);
        assertEquals(1, layers.size());
        return layers.get(0);
    }

    private static MouseEvent generateMouseEvent(boolean shift, double lat, double lon) {
        final var mv = MainApplication.getMap().mapView;
        while (mv.getScale() > 10) {
            mv.zoomIn();
        }
        final var ll = new LatLon(lat, lon);
        mv.zoomTo(ll);
        final var point = mv.getPoint(ll);
        return new MouseEvent(MainApplication.getMap(), UUID.randomUUID().hashCode(), System.currentTimeMillis(),
                shift ? MouseEvent.SHIFT_DOWN_MASK : 0, point.x, point.y, 1, false, MouseEvent.BUTTON1);
    }

    private static void sync() throws InterruptedException, ExecutionException, TimeoutException {
        MainApplication.worker.submit(() -> {
            /* Sync thread */ }).get(5, TimeUnit.SECONDS);
        final CountDownLatch cdl = new CountDownLatch(2);
        GuiHelper.runInEDTAndWait(cdl::countDown);
        MainApplication.worker.execute(cdl::countDown);
        assertTrue(cdl.await(10, TimeUnit.SECONDS));
    }

    /**
     * A new load replaces the statuses sent before it, for the tasks it loads (#146)
     */
    @Test
    void testNewLayerClearsSentStatuses() {
        ModifiedObjects.setSentStatus(900_156L, TaskStatus.FIXED);
        ModifiedObjects.setSentStatus(900_157L, TaskStatus.SKIPPED);
        try {
            final var point = new ClusteredPoint(900_156L, 1, "", "n", 10, "Benches", new Point(0, 0), null, "",
                    Instant.EPOCH, null, 2, TaskStatus.CREATED, null, null, null, null,
                    new PointReview(null, null, null, null, null, null, null, null, null), 0, null, false, null,
                    Map.of());
            new MapRouletteClusteredPointLayer(new Bounds(0, 0, 0, 0), List.of(point));
            assertNull(ModifiedObjects.getSentStatus(900_156L), "Loaded again: the server's status counts");
            assertEquals(TaskStatus.SKIPPED, ModifiedObjects.getSentStatus(900_157L), "Not in this load");
        } finally {
            ModifiedObjects.clearSentStatuses(List.of(900_156L, 900_157L));
        }
    }
}
