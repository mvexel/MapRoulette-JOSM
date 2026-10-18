// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.command.PurgeCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.PrimitiveId;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.plugins.maproulette.data.TaskPrimitives;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;
import org.openstreetmap.josm.testutils.annotations.Main;
import org.openstreetmap.josm.testutils.annotations.Projection;

import mockit.Mock;
import mockit.MockUp;

/**
 * Test class for {@link TaskEdits}
 */
@BasicPreferences
@Main
@Projection
class TaskEditsTest {
    private static final Task TASK = new Task(27, "Bench 27", null, null, 15318, null, null, null, null, null, null,
            null, null, null, 0, null, null, null, false, null, null);
    private static final Task NEXT_TASK = new Task(28, "Bench 28", null, null, 15318, null, null, null, null, null,
            null, null, null, null, 0, null, null, null, false, null, null);

    @AfterEach
    void tearDown() {
        ModifiedObjects.forgetAll();
        sync();
        UndoRedoHandler.getInstance().clean();
    }

    /**
     * Only edits made while a task is started count: downloaded objects don't, undone edits drop out, and new objects
     * count (#115)
     */
    @Test
    void testEditsWhileStarted() {
        TaskEdits.start();
        final var downloaded = new Node(LatLon.ZERO);
        downloaded.setOsmId(1, 1);
        final var dataSet = new DataSet();
        dataSet.addPrimitive(downloaded);
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(dataSet, "testEditsWhileStarted", null));
        UndoRedoHandler.getInstance().add(new ChangePropertyCommand(downloaded, "amenity", "bench"));
        sync();
        assertEquals(0, TaskEdits.count(), "Edited before the task was started");

        ModifiedObjects.addLockedTask(TASK);
        sync();
        final var other = new Node(LatLon.NORTH_POLE);
        other.setOsmId(2, 1);
        dataSet.addPrimitive(other);
        assertEquals(0, TaskEdits.count(), "A downloaded object is not an edit");

        UndoRedoHandler.getInstance().add(new ChangePropertyCommand(other, "backrest", "yes"));
        assertEquals(1, TaskEdits.count());
        assertTrue(TaskEdits.touches(List.of(other)));
        assertFalse(TaskEdits.touches(List.of(downloaded)), "The edit before Start is not the task's");

        UndoRedoHandler.getInstance().undo();
        assertEquals(0, TaskEdits.count(), "An undone edit is not uploaded");

        final var bench = new Node(LatLon.ZERO);
        UndoRedoHandler.getInstance().add(new AddCommand(dataSet, bench));
        assertEquals(1, TaskEdits.count(), "A new object is an edit");
        assertTrue(TaskEdits.touches(List.of(bench)));

        ModifiedObjects.removeLockedTask(TASK);
        sync();
        assertEquals(0, TaskEdits.count(), "A stopped task's edits no longer count");
        assertFalse(TaskEdits.touches(List.of(bench)));
    }

    /**
     * A command that edits many objects at once reaches the listener as one bulk event, and its edits count too
     */
    @Test
    void testBulkEdit() {
        TaskEdits.start();
        final var dataSet = new DataSet();
        final var nodes = new ArrayList<Node>();
        for (var i = 1; i <= 40; i++) {
            final var node = new Node(new LatLon(0, i / 1000d));
            node.setOsmId(i, 1);
            dataSet.addPrimitive(node);
            nodes.add(node);
        }
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(dataSet, "testBulkEdit", null));
        ModifiedObjects.addLockedTask(TASK);
        sync();

        UndoRedoHandler.getInstance().add(new ChangePropertyCommand(nodes, "amenity", "bench"));
        assertEquals(40, TaskEdits.count());
        assertTrue(TaskEdits.touches(List.of(nodes.get(39))));
    }

    /**
     * Undoing or redoing an edit made before Start is not task work (#130)
     */
    @Test
    void testUndoRedoOfEditBeforeStart() {
        TaskEdits.start();
        final var bench = new Node(LatLon.ZERO);
        bench.setOsmId(3, 1);
        final var dataSet = new DataSet();
        dataSet.addPrimitive(bench);
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(dataSet, "testUndoRedoOfEditBeforeStart", null));
        UndoRedoHandler.getInstance().add(new ChangePropertyCommand(bench, "amenity", "bench"));
        ModifiedObjects.addLockedTask(TASK);
        sync();
        assertEquals(0, TaskEdits.count());

        UndoRedoHandler.getInstance().undo();
        UndoRedoHandler.getInstance().redo();
        assertEquals(0, TaskEdits.count(), "Redoing an edit made before Start");
        assertFalse(TaskEdits.touches(List.of(bench)));

        UndoRedoHandler.getInstance().add(new ChangePropertyCommand(bench, "backrest", "yes"));
        assertEquals(1, TaskEdits.count(), "A new edit of the same object counts");
        assertTrue(TaskEdits.touches(List.of(bench)));
    }

    /**
     * New objects that come in with a merged layer were not drawn for the task; new objects an edit adds after Start,
     * or brings back with an undo, were (#130)
     */
    @Test
    void testNewObjectsFromMerge() {
        TaskEdits.start();
        final var dataSet = new DataSet();
        final var layer = new OsmDataLayer(dataSet, "testNewObjectsFromMerge", null);
        MainApplication.getLayerManager().addLayer(layer);
        final var drawnBefore = new DataSet();
        drawnBefore.addPrimitive(new Node(LatLon.NORTH_POLE));
        ModifiedObjects.addLockedTask(TASK);
        sync();

        GuiHelper.runInEDTAndWait(() -> layer.mergeFrom(drawnBefore));
        assertEquals(1, dataSet.getNodes().size());
        assertEquals(0, TaskEdits.count(), "Objects merged from another layer");

        final var bench = new Node(LatLon.ZERO);
        UndoRedoHandler.getInstance().add(new AddCommand(dataSet, bench));
        assertEquals(1, TaskEdits.count());
        UndoRedoHandler.getInstance().undo();
        UndoRedoHandler.getInstance().redo();
        assertTrue(TaskEdits.touches(List.of(bench)), "Redoing an edit made after Start");
        assertEquals(1, TaskEdits.count());
    }

    /**
     * Objects downloaded from OSM that come back with an undo are not edits (#130)
     */
    @Test
    void testUndonePurgeIsNotAnEdit() {
        TaskEdits.start();
        final var bench = new Node(LatLon.ZERO);
        bench.setOsmId(5, 1);
        final var dataSet = new DataSet();
        dataSet.addPrimitive(bench);
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(dataSet, "testUndonePurgeIsNotAnEdit", null));
        ModifiedObjects.addLockedTask(TASK);
        sync();
        UndoRedoHandler.getInstance().add(PurgeCommand.build(List.of(bench), null));
        assertTrue(dataSet.getNodes().isEmpty());
        UndoRedoHandler.getInstance().undo();
        assertEquals(List.of(bench), List.copyOf(dataSet.getNodes()));
        assertFalse(TaskEdits.touches(List.of(bench)), "A downloaded object put back");
        assertEquals(0, TaskEdits.count());
    }

    /**
     * A removed layer is no longer followed: edits in it don't count (#130)
     */
    @Test
    void testRemovedLayerNotFollowed() {
        TaskEdits.start();
        final var bench = new Node(LatLon.ZERO);
        bench.setOsmId(4, 1);
        final var dataSet = new DataSet();
        dataSet.addPrimitive(bench);
        final var layer = new OsmDataLayer(dataSet, "testRemovedLayerNotFollowed", null);
        MainApplication.getLayerManager().addLayer(layer);
        // Another layer keeps the map open
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(new DataSet(), "other", null));
        ModifiedObjects.addLockedTask(TASK);
        sync();
        MainApplication.getLayerManager().removeLayer(layer);
        assertEquals(List.of(TASK), ModifiedObjects.getLockedTasks());
        bench.put("amenity", "bench");
        assertEquals(0, TaskEdits.count(), "An edit in a removed layer");
        assertFalse(TaskEdits.touches(List.of(bench)));
    }

    /**
     * The next task starts with no edits, even if it was started before the lock listener learnt that the last one
     * ended; undoing the last task's edit is not work on the next one (#130)
     */
    @Test
    void testNextTaskStartsWithoutEdits() {
        TaskEdits.start();
        final var bench = new Node(LatLon.ZERO);
        bench.setOsmId(6, 1);
        final var dataSet = new DataSet();
        dataSet.addPrimitive(bench);
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(dataSet, "testNextTaskStartsWithoutEdits", null));
        ModifiedObjects.addLockedTask(TASK);
        sync();
        UndoRedoHandler.getInstance().add(new ChangePropertyCommand(bench, "amenity", "bench"));
        assertEquals(1, TaskEdits.count());

        // As the auto-start after Fixed does: both changes reach the listener after the next task is started
        ModifiedObjects.removeLockedTask(TASK);
        ModifiedObjects.addLockedTask(NEXT_TASK);
        sync();
        assertEquals(List.of(NEXT_TASK), ModifiedObjects.getLockedTasks());
        assertEquals(0, TaskEdits.count(), "The last task's edit");
        UndoRedoHandler.getInstance().undo();
        UndoRedoHandler.getInstance().redo();
        assertEquals(0, TaskEdits.count(), "Undoing and redoing the last task's edit");
        assertFalse(TaskEdits.touches(List.of(bench)));
    }

    /**
     * An object both tasks name: the next task's changes are shown against the object as the last task left it (#130)
     */
    @Test
    void testNextTaskBaselineOfSharedObject() {
        TaskEdits.start();
        final var bench = new Node(LatLon.ZERO);
        bench.setOsmId(8, 1);
        final var dataSet = new DataSet();
        dataSet.addPrimitive(bench);
        new MockUp<TaskPrimitives>() {
            @Mock
            Optional<Collection<PrimitiveId>> cachedPrimitiveIds(Task task) {
                return Optional.of(List.of(bench.getPrimitiveId()));
            }
        };
        MainApplication.getLayerManager()
                .addLayer(new OsmDataLayer(dataSet, "testNextTaskBaselineOfSharedObject", null));
        ModifiedObjects.addLockedTask(TASK);
        sync();
        UndoRedoHandler.getInstance().add(new ChangePropertyCommand(bench, "amenity", "bench"));

        // As the auto-start after Fixed does: both changes reach the listener after the next task is started
        ModifiedObjects.removeLockedTask(TASK);
        ModifiedObjects.addLockedTask(NEXT_TASK);
        sync();
        UndoRedoHandler.getInstance().add(new ChangePropertyCommand(bench, "backrest", "yes"));
        assertEquals("bench", TaskEdits.baseline(bench).get("amenity"), "The last task's edit is in the baseline");
        assertEquals(null, TaskEdits.baseline(bench).get("backrest"));
    }

    /**
     * Commands JOSM drops from its undo list are forgotten, and edits made since Start still count (#130)
     */
    @Test
    void testDroppedCommandsForgotten() {
        TaskEdits.start();
        final var dataSet = new DataSet();
        final var bench = new Node(LatLon.ZERO);
        bench.setOsmId(7, 1);
        dataSet.addPrimitive(bench);
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(dataSet, "testDroppedCommandsForgotten", null));
        ModifiedObjects.addLockedTask(TASK);
        sync();
        UndoRedoHandler.getInstance().add(new ChangePropertyCommand(bench, "amenity", "bench"));
        UndoRedoHandler.getInstance().clean();
        assertEquals(0, TaskEdits.commandsSinceStart(), "Cleaned from the undo list");
        assertEquals(1, TaskEdits.count(), "Still an edit");
    }

    private static void sync() {
        GuiHelper.runInEDTAndWait(() -> {
            /* Sync EDT */ });
    }
}
