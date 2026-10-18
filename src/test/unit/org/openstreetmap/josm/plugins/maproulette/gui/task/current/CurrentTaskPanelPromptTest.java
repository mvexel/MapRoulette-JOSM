// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui.task.current;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.util.List;

import javax.swing.JButton;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.gui.widgets.JMultilineLabel;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedObjects;
import org.openstreetmap.josm.plugins.maproulette.gui.TaskListPanelTest;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig;
import org.openstreetmap.josm.testutils.annotations.Main;
import org.openstreetmap.josm.testutils.annotations.Projection;

/**
 * A question in the state line goes away once answered or once the panel shows another task, so it doesn't come back
 * stale (#166)
 */
@Main
@MapRouletteConfig
@Projection
class CurrentTaskPanelPromptTest {
    private static final String TEXT = "Challenge \"Benches\" isn't published yet.";

    private static Task task(long id) {
        return new Task(id, "node/1", null, null, 11, null, null, new DataSet(), null, null, null, null, null, null, 0,
                null, null, null, false, null, null);
    }

    @Test
    void testPromptDroppedWhenAnotherTaskIsShown() {
        final var panel = GuiHelper.runInEDTAndWaitAndReturn(CurrentTaskPanel::new);
        try {
            final var first = task(241);
            GuiHelper.runInEDTAndWait(() -> {
                panel.refreshModel(first);
                assertFalse(panel.askHere(new CurrentTaskPanel.Prompt(242, false, TEXT, null, List.of())),
                        "Not the shown task");
                assertTrue(panel.askHere(new CurrentTaskPanel.Prompt(241, false, TEXT, null, List.of())));
            });
            assertTrue(stateText(panel).contains("published"));
            GuiHelper.runInEDTAndWait(() -> {
                panel.refreshModel(task(242));
                panel.refreshModel(first);
            });
            assertFalse(stateText(panel).contains("published"), "Not shown again for the first task");
        } finally {
            GuiHelper.runInEDTAndWait(panel::destroy);
        }
    }

    @Test
    void testAnswerClearsPrompt() {
        final var panel = GuiHelper.runInEDTAndWaitAndReturn(CurrentTaskPanel::new);
        try {
            final var ok = new CurrentTaskPanel.Choice("OK", null, () -> {
                // Nothing to do
            });
            GuiHelper.runInEDTAndWait(() -> {
                panel.refreshModel(task(241));
                panel.askHere(new CurrentTaskPanel.Prompt(241, false, TEXT, null, List.of(ok)));
            });
            assertTrue(stateText(panel).contains("published"));
            final var button = TaskListPanelTest.find(panel, JButton.class, b -> "OK".equals(b.getText()));
            GuiHelper.runInEDTAndWait(button::doClick);
            assertFalse(stateText(panel).contains("published"));
        } finally {
            GuiHelper.runInEDTAndWait(panel::destroy);
        }
    }

    /**
     * A question about a task that wasn't started, such as why its start failed, goes once a retry starts it
     */
    @Test
    void testPromptDroppedWhenTaskIsStarted() {
        final var panel = GuiHelper.runInEDTAndWaitAndReturn(CurrentTaskPanel::new);
        final var task = task(241);
        try {
            GuiHelper.runInEDTAndWait(() -> {
                panel.refreshModel(task);
                panel.askHere(new CurrentTaskPanel.Prompt(241, false, "Task 241 couldn't be started. Try again.", null,
                        List.of()));
            });
            assertTrue(stateText(panel).contains("Try again"));
            GuiHelper.runInEDTAndWait(() -> ModifiedObjects.addLockedTask(task));
            GuiHelper.runInEDTAndWait(() -> {
                /* Sync EDT */ });
            assertFalse(stateText(panel).contains("Try again"), "The started task's own state shows");
        } finally {
            GuiHelper.runInEDTAndWait(() -> ModifiedObjects.removeLockedTask(task));
            GuiHelper.runInEDTAndWait(panel::destroy);
        }
    }

    private static boolean isShown(Component component) {
        for (var c = component; c != null; c = c.getParent()) {
            if (!c.isVisible()) {
                return false;
            }
        }
        return true;
    }

    private static String stateText(CurrentTaskPanel panel) {
        final var texts = new StringBuilder();
        // Only what shows: a hidden state line keeps its last text
        TaskListPanelTest.findOrNull(panel, JMultilineLabel.class, label -> {
            if (isShown(label)) {
                texts.append(label.getText());
            }
            return false;
        });
        return texts.toString();
    }
}
