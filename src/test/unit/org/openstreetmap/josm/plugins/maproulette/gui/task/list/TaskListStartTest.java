// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui.task.list;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import javax.swing.JTable;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.plugins.maproulette.api.model.Point;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.plugins.maproulette.gui.TaskListPanelTest;
import org.openstreetmap.josm.plugins.maproulette.gui.layer.MapRouletteClusteredPointLayer;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig;
import org.openstreetmap.josm.testutils.annotations.Main;
import org.openstreetmap.josm.testutils.annotations.Projection;

import mockit.Mock;
import mockit.MockUp;

/**
 * {@link TaskListPanel#start(Task)} starts a task that may not be in the list yet, such as the next task (#117)
 */
@Main
@MapRouletteConfig
@Projection
class TaskListStartTest {
    private static final Task NEXT = new Task(242, "node/4365677673", null, null, 11, null,
            new Point(40.7614, -111.8838), new DataSet(), null, null, null, null, null, null, 0, null, null, null,
            false, null, null);

    /**
     * The task joins the list, and Start runs on its row
     */
    @Test
    void testStartAddsToList() {
        MainApplication.getLayerManager().addLayer(new MapRouletteClusteredPointLayer(new Bounds(0, 0, 0, 0),
                List.of(TaskListPanelTest.point(1001, 27905, "SLC benches", null))));
        final var started = mockStart();
        final var panel = GuiHelper.runInEDTAndWaitAndReturn(TaskListPanel::new);
        try {
            GuiHelper.runInEDTAndWait(() -> panel.start(NEXT));
            final var layer = MainApplication.getLayerManager().getLayersOfType(MapRouletteClusteredPointLayer.class)
                    .get(0);
            assertTrue(layer.getTasks().contains(NEXT), "The next task is in the list");
            assertEquals(1, started.size());
            final var table = TaskListPanelTest.find(panel, JTable.class, t -> true);
            final int[] rows = started.get(0);
            assertEquals(1, rows.length, "Start runs on the task's row");
            assertEquals(242,
                    ((TaskTableModel) table.getModel()).get(table.getRowSorter().convertRowIndexToModel(rows[0])).id());
        } finally {
            panel.destroy();
        }
    }

    /**
     * With no task list loaded, the task gets a list of its own
     */
    @Test
    void testStartWithoutList() {
        final var started = mockStart();
        final var panel = GuiHelper.runInEDTAndWaitAndReturn(TaskListPanel::new);
        try {
            GuiHelper.runInEDTAndWait(() -> panel.start(NEXT));
            final var layers = MainApplication.getLayerManager().getLayersOfType(MapRouletteClusteredPointLayer.class);
            assertEquals(1, layers.size());
            assertTrue(layers.get(0).getTasks().contains(NEXT));
            assertEquals(1, started.size());
        } finally {
            panel.destroy();
        }
    }

    private static List<int[]> mockStart() {
        final var started = new ArrayList<int[]>();
        new MockUp<LockUnlockTaskAction>() {
            @Mock
            void start(Task task, int[] rows) {
                assertEquals(NEXT, task);
                started.add(rows);
            }
        };
        return started;
    }
}
