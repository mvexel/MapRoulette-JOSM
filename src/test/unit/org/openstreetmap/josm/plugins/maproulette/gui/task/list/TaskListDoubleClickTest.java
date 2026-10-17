// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui.task.list;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.Action;
import javax.swing.JTable;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.SideButton;
import org.openstreetmap.josm.plugins.maproulette.api.auth.MapRouletteAuth;
import org.openstreetmap.josm.plugins.maproulette.gui.TaskListPanelTest;
import org.openstreetmap.josm.plugins.maproulette.gui.layer.MapRouletteClusteredPointLayer;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig;
import org.openstreetmap.josm.testutils.annotations.Main;
import org.openstreetmap.josm.testutils.annotations.Projection;

import mockit.Mock;
import mockit.MockUp;

/**
 * Double-clicking a row of {@link TaskListPanel} starts the task (#112)
 */
@Main
@MapRouletteConfig
@Projection
class TaskListDoubleClickTest {
    @Test
    void testDoubleClickStarts() {
        MainApplication.getLayerManager().addLayer(new MapRouletteClusteredPointLayer(new Bounds(0, 0, 0, 0),
                List.of(TaskListPanelTest.point(1, 18, "SLC benches", null))));
        new MockUp<MapRouletteAuth>() {
            @Mock
            boolean isAvailable() {
                return true;
            }
        };
        final var started = new AtomicInteger();
        new MockUp<LockUnlockTaskAction>() {
            @Mock
            void actionPerformed(ActionEvent e) {
                started.incrementAndGet();
            }
        };
        final var panel = new TaskListPanel();
        try {
            final var table = TaskListPanelTest.find(panel, JTable.class, t -> true);
            final var action = TaskListPanelTest
                    .find(panel, SideButton.class, b -> b.getAction() instanceof LockUnlockTaskAction).getAction();
            table.setSize(300, 300);
            table.getSelectionModel().setSelectionInterval(0, 0);
            assertEquals("Start Task", action.getValue(Action.NAME));
            final var cell = table.getCellRect(0, 0, true);
            for (var clicks = 1; clicks <= 2; clicks++) {
                for (var listener : table.getMouseListeners()) {
                    listener.mouseClicked(new MouseEvent(table, MouseEvent.MOUSE_CLICKED, 0,
                            InputEvent.BUTTON1_DOWN_MASK, cell.x + 5, cell.y + 5, clicks, false, MouseEvent.BUTTON1));
                }
            }
            assertEquals(1, started.get(), "Only the double click starts the task");
        } finally {
            panel.destroy();
        }
    }
}
