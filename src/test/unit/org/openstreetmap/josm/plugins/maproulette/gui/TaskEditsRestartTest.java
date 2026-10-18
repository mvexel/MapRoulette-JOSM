// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.plugins.maproulette.api.TaskAPI;
import org.openstreetmap.josm.plugins.maproulette.api_caching.ChallengeCache;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig;
import org.openstreetmap.josm.testutils.annotations.Main;
import org.openstreetmap.josm.testutils.annotations.Projection;

/**
 * {@link TaskEdits} after a restart: the autosave brings back edits made before the task is started again (#141)
 */
@Main
@MapRouletteConfig
@Projection
class TaskEditsRestartTest {
    @AfterEach
    void tearDown() {
        ModifiedObjects.forgetAll();
        sync();
        UndoRedoHandler.getInstance().clean();
    }

    /**
     * Unuploaded edits of the task's own object count once the task is started; other earlier edits still don't
     *
     * @throws Exception if the test fails
     */
    @Test
    void testEarlierEditOfTaskObjectCounts() throws Exception {
        TaskEdits.start();
        // Task 132279499 (challenge 27905) is about node 9494185766
        final var taskNode = new Node(LatLon.ZERO);
        taskNode.setOsmId(9494185766L, 1);
        final var otherNode = new Node(LatLon.NORTH_POLE);
        otherNode.setOsmId(1, 1);
        final var dataSet = new DataSet();
        dataSet.addPrimitive(taskNode);
        dataSet.addPrimitive(otherNode);
        MainApplication.getLayerManager()
                .addLayer(new OsmDataLayer(dataSet, "testEarlierEditOfTaskObjectCounts", null));
        // As restored from the autosave: edited, not uploaded
        UndoRedoHandler.getInstance().add(new ChangePropertyCommand(List.of(taskNode, otherNode), "backrest", "yes"));
        ChallengeCache.challenge(27905);
        final var task = TaskAPI.get(132279499L);

        ModifiedObjects.addLockedTask(task);
        sync();
        assertEquals(1, TaskEdits.count());
        assertTrue(TaskEdits.touches(List.of(taskNode)));
        assertFalse(TaskEdits.touches(List.of(otherNode)), "An earlier edit of another object is not the task's");
    }

    private static void sync() {
        GuiHelper.runInEDTAndWait(() -> {
            /* Sync EDT */ });
    }
}
