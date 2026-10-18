// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui.task.list;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.List;

import javax.swing.JTable;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.plugins.maproulette.gui.TaskListPanelTest;
import org.openstreetmap.josm.plugins.maproulette.gui.layer.MapRouletteClusteredPointLayer;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig;
import org.openstreetmap.josm.testutils.annotations.Main;
import org.openstreetmap.josm.testutils.annotations.Projection;

/**
 * A right-click in the task list selects the row under the mouse first, so the menu acts on that task (#195)
 */
@Main
@MapRouletteConfig
@Projection
class TaskListRightClickTest {
    @Test
    void testRightClickSelectsRow() {
        MainApplication.getLayerManager()
                .addLayer(new MapRouletteClusteredPointLayer(new Bounds(0, 0, 0, 0),
                        List.of(TaskListPanelTest.point(1001, 27905, "SLC benches", null),
                                TaskListPanelTest.point(1002, 27905, "SLC benches", null),
                                TaskListPanelTest.point(1003, 27905, "SLC benches", null))));
        // Built on the EDT, as JOSM does (see TaskListDoubleClickTest)
        final var panel = GuiHelper.runInEDTAndWaitAndReturn(TaskListPanel::new);
        GuiHelper.runInEDTAndWait(() -> {
            /* Sync EDT */ });
        try {
            final var table = TaskListPanelTest.find(panel, JTable.class, t -> true);
            table.setSize(300, 300);
            final var first = table.getRowCount() - 3;
            table.setRowSelectionInterval(first, first);
            rightPress(table, first + 1);
            assertArrayEquals(new int[] { first + 1 }, table.getSelectedRows(), "Another row is selected first");

            table.setRowSelectionInterval(first, first + 1);
            rightPress(table, first);
            assertArrayEquals(new int[] { first, first + 1 }, table.getSelectedRows(),
                    "A selected row keeps the selection");

            table.setRowSelectionInterval(first, first);
            GuiHelper.runInEDTAndWait(() -> table.getPopupLocation(null));
            assertArrayEquals(new int[] { first }, table.getSelectedRows(), "The menu key keeps the selection");
        } finally {
            panel.destroy();
        }
    }

    /**
     * Open the menu with the mouse over a row. Swing asks the table where to show its menu, with the mouse event,
     * whichever press or release triggers it.
     */
    private static void rightPress(JTable table, int row) {
        final var cell = table.getCellRect(row, 0, true);
        final var event = new MouseEvent(table, MouseEvent.MOUSE_PRESSED, 0, InputEvent.BUTTON3_DOWN_MASK, cell.x + 5,
                cell.y + 5, 1, true, MouseEvent.BUTTON3);
        GuiHelper.runInEDTAndWait(() -> table.getPopupLocation(event));
    }
}
