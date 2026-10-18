// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui.task.current;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import javax.swing.JButton;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.plugins.maproulette.api.enums.TaskStatus;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedObjects;
import org.openstreetmap.josm.plugins.maproulette.gui.TaskListPanelTest;
import org.openstreetmap.josm.plugins.maproulette.gui.TaskResults;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig;
import org.openstreetmap.josm.testutils.annotations.Main;
import org.openstreetmap.josm.testutils.annotations.Projection;

import mockit.Mock;
import mockit.MockUp;

/**
 * Once the shown task is finished, {@link CurrentTaskPanel} offers the next task instead of the instructions and the
 * finish strip (#117)
 */
@Main
@MapRouletteConfig
@Projection
class CurrentTaskPanelNextTest {
    private static final Task TASK = new Task(241, "node/4365677676", null, null, 11, null, null, new DataSet(), null,
            null, null, null, null, null, 0, null, null, null, false, null, null);

    @AfterEach
    void tearDown() {
        ModifiedObjects.removeLockedTask(TASK);
        TaskResults.clearAll();
        NextTaskPanel.AUTO_START.remove();
    }

    @Test
    void testNextAfterFinish() {
        final var offers = mockOffers();
        final var panel = GuiHelper.runInEDTAndWaitAndReturn(CurrentTaskPanel::new);
        try {
            GuiHelper.runInEDTAndWait(() -> {
                ModifiedObjects.addLockedTask(TASK);
                panel.refreshModel(TASK);
            });
            final var next = TaskListPanelTest.find(panel, NextTaskPanel.class, p -> true);
            final var upload = TaskListPanelTest.find(panel, JButton.class,
                    b -> "Upload edits and finish…".equals(b.getText()));
            assertFalse(next.isShowing() && next.isVisible() && isCardShown(next),
                    "A started task shows its instructions");
            assertTrue(upload.getParent().isVisible());
            assertTrue(offers.isEmpty());

            GuiHelper.runInEDTAndWait(() -> {
                ModifiedObjects.removeLockedTask(TASK);
                TaskResults.sent(TASK, TaskStatus.SKIPPED);
            });
            GuiHelper.runInEDTAndWait(() -> {
                /* Sync EDT */ });
            assertTrue(isCardShown(next), "The finished task shows the next one");
            assertFalse(upload.getParent().isVisible(), "The finish strip is hidden");
            assertEquals(List.of(Boolean.FALSE), offers.stream().distinct().toList(), "Off by default");
        } finally {
            GuiHelper.runInEDTAndWait(panel::destroy);
        }
    }

    /**
     * With the option on, the next task starts on its own once after Fixed, not when the finished task is shown again
     */
    @Test
    void testAutoStartOnce() {
        NextTaskPanel.AUTO_START.put(true);
        final var offers = mockOffers();
        final var panel = GuiHelper.runInEDTAndWaitAndReturn(CurrentTaskPanel::new);
        try {
            GuiHelper.runInEDTAndWait(() -> {
                panel.refreshModel(TASK);
                TaskResults.sent(TASK, TaskStatus.FIXED);
            });
            GuiHelper.runInEDTAndWait(() -> {
                /* Sync EDT */ });
            GuiHelper.runInEDTAndWait(() -> panel.refreshModel(TASK));
            assertTrue(offers.size() >= 2, offers.toString());
            assertEquals(Boolean.TRUE, offers.get(0));
            assertEquals(List.of(Boolean.FALSE), offers.subList(1, offers.size()).stream().distinct().toList());
        } finally {
            GuiHelper.runInEDTAndWait(panel::destroy);
        }
    }

    private static boolean isCardShown(NextTaskPanel next) {
        // The scroll pane of the card
        return next.getParent().getParent().isVisible();
    }

    private static List<Boolean> mockOffers() {
        final var offers = new ArrayList<Boolean>();
        new MockUp<NextTaskPanel>() {
            @Mock
            void show(Task task, boolean autoStart) {
                assertEquals(TASK, task);
                offers.add(autoStart);
            }
        };
        return offers;
    }
}
