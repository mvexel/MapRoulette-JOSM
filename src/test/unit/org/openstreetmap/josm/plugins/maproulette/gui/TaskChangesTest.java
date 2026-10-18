// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.command.DeleteCommand;
import org.openstreetmap.josm.command.MoveCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.plugins.maproulette.api.TaskAPI;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.plugins.maproulette.api_caching.ChallengeCache;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig;
import org.openstreetmap.josm.testutils.annotations.Main;
import org.openstreetmap.josm.testutils.annotations.Projection;
import org.openstreetmap.josm.testutils.annotations.TaggingPresets;

/**
 * Test class for {@link TaskChanges}
 */
@Main
@MapRouletteConfig
@Projection
@TaggingPresets
class TaskChangesTest {
    /** Task 132279499 (challenge 27905) asks to remove the building tag of node 9494185766 */
    private static final LatLon TASK_PLACE = new LatLon(39.0745811, -108.5704318);

    @AfterEach
    void tearDown() {
        ModifiedObjects.forgetAll();
        sync();
        UndoRedoHandler.getInstance().clean();
    }

    /**
     * Instruction keys, from real instructions and the forms Ada listed: {@code key=value}, {@code key=*} and
     * {@code `key`}, leaving out placeholders and links
     *
     * @param instruction The instruction
     * @param expected    The keys, separated by spaces
     */
    @ParameterizedTest
    @CsvSource(value = {
            "1. Node 9494185766 has been tagged as a building but it appears to be an address. Please remove the"
                    + " building=< * > tag.|building",
            "Check whether this bench has a backrest. If it does, add **backrest=yes**; if not, add backrest=no.|backrest",
            "Set `surface` and smoothness=*|smoothness surface", "Name it {{name}} (see https://example.org/?a=b)|",
            "No tags here, 2=3 is maths|", "Check the `addr:street` tag|addr:street" }, delimiter = '|')
    void testInstructionKeys(String instruction, String expected) {
        assertEquals(expected == null ? Set.of() : new TreeSet<>(List.of(expected.split(" "))),
                new TreeSet<>(TaskChanges.instructionKeys(instruction)));
    }

    /**
     * The finish question lists the tag changes on the task's object and the objects created near it, and hints at a
     * tag the instructions name that didn't change (#148)
     *
     * @throws Exception if the test fails
     */
    @Test
    void testChangesOfTaskObject() throws Exception {
        final var dataSet = new DataSet();
        final var taskNode = taskNode(dataSet);
        final var task = startTask();
        final var name = TaskChanges.name(taskNode);
        var summary = TaskChanges.of(task, dataSet);
        assertEquals(List.of("No changes to " + name + "."), summary.lines());
        assertEquals("The instructions mention building, which you didn't change.", summary.warning());

        UndoRedoHandler.getInstance().add(new ChangePropertyCommand(taskNode, "building", null));
        UndoRedoHandler.getInstance().add(new ChangePropertyCommand(taskNode, "addr:housenumber", "12"));
        UndoRedoHandler.getInstance().add(new ChangePropertyCommand(taskNode, "width", "3"));
        summary = TaskChanges.of(task, dataSet);
        assertEquals(List.of("Node 9494185766: + addr:housenumber=12 · − building=window · ~ width=2 → 3"),
                summary.lines());
        assertNull(summary.warning(), "The building tag changed");

        // A new object near the task; one far from it is left out
        final var bench = new Node(TASK_PLACE);
        bench.put("amenity", "bench");
        bench.put("backrest", "no");
        final var far = new Node(new LatLon(TASK_PLACE.lat() + 0.01, TASK_PLACE.lon()));
        far.put("amenity", "bench");
        UndoRedoHandler.getInstance().add(new AddCommand(dataSet, bench));
        UndoRedoHandler.getInstance().add(new AddCommand(dataSet, far));
        sync();
        summary = TaskChanges.of(task, dataSet);
        assertEquals(2, summary.lines().size(), summary.lines().toString());
        assertEquals("New: " + TaskChanges.name(bench) + " (amenity=bench, backrest=no)", summary.lines().get(1));

        // Undoing back to the start shows no changes again
        UndoRedoHandler.getInstance().undo(5);
        sync();
        assertEquals(List.of("No changes to " + name + "."), TaskChanges.of(task, dataSet).lines());
    }

    /**
     * An edit of the task's object made before Start is part of the baseline, so it is not shown as no change
     *
     * @throws Exception if the test fails
     */
    @Test
    void testChangedBeforeStart() throws Exception {
        final var dataSet = new DataSet();
        final var taskNode = taskNode(dataSet);
        UndoRedoHandler.getInstance().add(new ChangePropertyCommand(taskNode, "building", null));
        final var task = startTask();
        assertEquals(List.of(TaskChanges.name(taskNode) + ": changed before you started, not uploaded yet"),
                TaskChanges.of(task, dataSet).lines());

        UndoRedoHandler.getInstance().add(new MoveCommand(taskNode, 0.0001, 0));
        assertEquals(List.of(TaskChanges.name(taskNode) + ": moved"), TaskChanges.of(task, dataSet).lines());
    }

    /**
     * The usual path: Start, then the task's objects are downloaded into the open layer. Their state on arrival is
     * the baseline.
     *
     * @throws Exception if the test fails
     */
    @Test
    void testObjectsDownloadedAfterStart() throws Exception {
        final var dataSet = new DataSet();
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(dataSet, "TaskChangesTest", null));
        final var task = startTask();
        final var taskNode = new Node(TASK_PLACE);
        taskNode.setOsmId(9494185766L, 1);
        taskNode.put("building", "window");
        dataSet.addPrimitive(taskNode);
        sync();
        UndoRedoHandler.getInstance().add(new ChangePropertyCommand(taskNode, "building", null));
        assertEquals(List.of("Node 9494185766: \u2212 building=window"), TaskChanges.of(task, dataSet).lines());
    }

    /**
     * Deleting the task's object changes all its tags, so the instructions' tag is not "unchanged"
     *
     * @throws Exception if the test fails
     */
    @Test
    void testDeletedTaskObject() throws Exception {
        final var dataSet = new DataSet();
        final var taskNode = taskNode(dataSet);
        final var task = startTask();
        final var name = TaskChanges.name(taskNode);
        UndoRedoHandler.getInstance().add(new DeleteCommand(taskNode));
        final var summary = TaskChanges.of(task, dataSet);
        assertEquals(List.of(name + ": deleted"), summary.lines());
        assertNull(summary.warning());
    }

    /**
     * At most six lines, then how many more
     *
     * @throws Exception if the test fails
     */
    @Test
    void testAtMostSixLines() throws Exception {
        final var dataSet = new DataSet();
        taskNode(dataSet);
        final var task = startTask();
        for (var i = 0; i < 8; i++) {
            final var bench = new Node(new LatLon(TASK_PLACE.lat(), TASK_PLACE.lon() + i * 0.00001));
            bench.put("amenity", "bench");
            UndoRedoHandler.getInstance().add(new AddCommand(dataSet, bench));
        }
        sync();
        final var lines = TaskChanges.of(task, dataSet).lines();
        assertEquals(TaskChanges.MAX_LINES + 1, lines.size());
        assertEquals("and 2 more changes", lines.get(TaskChanges.MAX_LINES));
    }

    /**
     * Tag-fix tasks already say exactly what changes, and nothing is shown without data
     */
    @Test
    void testNothingForCooperativeTasksOrWithoutData() {
        final var cooperative = new Task(1, "", null, null, 1, null, null, new DataSet(), new Object(), null, null,
                null, null, null, 0, null, null, null, false, null, null);
        assertEquals(TaskChanges.Summary.NONE, TaskChanges.of(cooperative, new DataSet()));
        assertEquals(TaskChanges.Summary.NONE, TaskChanges.of(cooperative, null));
    }

    private static Node taskNode(DataSet dataSet) {
        final var taskNode = new Node(TASK_PLACE);
        taskNode.setOsmId(9494185766L, 1);
        taskNode.put("building", "window");
        taskNode.put("width", "2");
        dataSet.addPrimitive(taskNode);
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(dataSet, "TaskChangesTest", null));
        return taskNode;
    }

    private static Task startTask() throws Exception {
        TaskEdits.start();
        ChallengeCache.challenge(27905);
        final var task = TaskAPI.get(132279499L);
        ModifiedObjects.addLockedTask(task);
        sync();
        return task;
    }

    private static void sync() {
        GuiHelper.runInEDTAndWait(() -> {
            /* Sync EDT */ });
    }
}
