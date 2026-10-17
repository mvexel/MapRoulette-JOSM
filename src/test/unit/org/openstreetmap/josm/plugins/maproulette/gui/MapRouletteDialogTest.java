// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Container;
import java.time.Instant;

import javax.swing.JCheckBox;
import javax.swing.JLabel;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.SideButton;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.gui.widgets.JosmTextField;
import org.openstreetmap.josm.plugins.maproulette.api.enums.TaskStatus;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.plugins.maproulette.api.model.TaskReviewFields;
import org.openstreetmap.josm.plugins.maproulette.gui.task.current.CurrentTaskPanel;
import org.openstreetmap.josm.plugins.maproulette.gui.task.list.TaskListPanel;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;
import org.openstreetmap.josm.testutils.annotations.HTTP;
import org.openstreetmap.josm.testutils.annotations.Main;
import org.openstreetmap.josm.testutils.annotations.Projection;

/**
 * Test class for {@link MapRouletteDialog}
 */
@BasicPreferences
@HTTP
@Main
@MapRouletteConfig
@Projection
class MapRouletteDialogTest {
    /**
     * The task list and the current task are sections of one panel (#111)
     */
    @Test
    void testOnePanel() {
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(new DataSet(), "testOnePanel", null));
        final var dialog = new MapRouletteDialog();
        MainApplication.getMap().addToggleDialog(dialog);
        try {
            assertSame(dialog, MapRouletteDialog.get());
            assertSame(dialog.taskList(), TaskListPanel.get());
            assertSame(dialog.currentTask(), CurrentTaskPanel.get());
            assertNotNull(TaskListPanelTest.find(dialog, JLabel.class,
                    l -> l.getText() != null && l.getText().startsWith("Server: ")));
        } finally {
            MainApplication.getMap().removeToggleDialog(dialog);
        }
    }

    /**
     * Without loaded tasks, the task list shows how to get some (#111)
     */
    @Test
    void testEmptyState() {
        final var panel = new TaskListPanel();
        try {
            final var empty = TaskListPanelTest.find(panel, JLabel.class,
                    l -> "No tasks loaded yet".equals(l.getText()));
            assertTrue(empty.getParent().isVisible(), "The empty state should show while no tasks are loaded");
            assertFalse(TaskListPanelTest.getFilterField(panel).getParent().getParent().getParent().isVisible(),
                    "The table should be hidden while no tasks are loaded");
            assertFalse(TaskListPanelTest
                    .find(panel, SideButton.class, b -> b.getAction() == TaskListPanelTest.getDownloadAction(panel))
                    .getParent().getParent().isVisible(), "The empty state has its own buttons");
        } finally {
            panel.destroy();
        }
    }

    /**
     * The current task header names the challenge, the task and its id, and stays out of the scrolled instructions
     */
    @Test
    void testCurrentTaskHeader() {
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(new DataSet(), "testCurrentTaskHeader", null));
        final var task = new Task(27, "Bench 27", Instant.EPOCH, Instant.EPOCH, 1, "Check the bench", null,
                new DataSet(), null, TaskStatus.CREATED, null, null, null, null, 0, null, null, null, false, null, "");
        CurrentTaskPanel.show(task, false);
        final var dialog = MapRouletteDialog.get();
        assertNotNull(dialog, "Showing a task adds the panel if the map has none");
        try {
            final var panel = dialog.currentTask();
            assertNotNull(TaskListPanelTest.find(panel, JLabel.class, l -> "Task 27".equals(l.getText())));
            assertNotNull(TaskListPanelTest.find(panel, JLabel.class, l -> "Bench 27".equals(l.getText())));
            assertEquals("Challenge 1", ((JLabel) ((Container) panel.getComponent(0)).getComponent(0)).getText(),
                    "The challenge is not cached, so its id is shown");
        } finally {
            MainApplication.getMap().removeToggleDialog(dialog);
        }
    }

    /**
     * A started task keeps the list, so the panel never says nothing is loaded next to the current task (#111)
     */
    @Test
    void testNoEmptyStateWithStartedTask() {
        final var panel = new TaskListPanel();
        final var task = new Task(27, "Bench 27", Instant.EPOCH, Instant.EPOCH, 1, "Check the bench", null,
                new DataSet(), null, TaskStatus.CREATED, null, null, null, null, 0, null, null, null, false, null, "");
        try {
            ModifiedObjects.addLockedTask(task);
            GuiHelper.runInEDTAndWait(() -> {
                /* The lock listener runs on the EDT */ });
            assertFalse(TaskListPanelTest.find(panel, JLabel.class, l -> "No tasks loaded yet".equals(l.getText()))
                    .getParent().isVisible());
            ModifiedObjects.removeLockedTask(task);
            GuiHelper.runInEDTAndWait(() -> {
                /* The lock listener runs on the EDT */ });
            assertTrue(TaskListPanelTest.find(panel, JLabel.class, l -> "No tasks loaded yet".equals(l.getText()))
                    .getParent().isVisible());
        } finally {
            ModifiedObjects.forgetAll();
            panel.destroy();
        }
    }

    /**
     * Showing a task without focus, for example after a status change, does not reopen a closed panel
     */
    @Test
    void testRefreshKeepsPanelClosed() {
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(new DataSet(), "testRefresh", null));
        final var dialog = new MapRouletteDialog();
        MainApplication.getMap().addToggleDialog(dialog);
        try {
            if (dialog.isDialogShowing()) {
                dialog.hideDialog();
            }
            final var task = new Task(27, "Bench 27", Instant.EPOCH, Instant.EPOCH, 1, "Check the bench", null,
                    new DataSet(), null, TaskStatus.CREATED, null, null, null, null, 0, null, null, null, false, null,
                    "");
            CurrentTaskPanel.show(task, false);
            assertFalse(dialog.isDialogShowing());
        } finally {
            MainApplication.getMap().removeToggleDialog(dialog);
        }
    }

    /**
     * The comment and the review request are filled in the panel and sent with the status. A skip sends only the
     * comment (#106, #115).
     */
    @Test
    void testFeedbackFromPanel() {
        final var panel = new CurrentTaskPanel();
        final var task = new Task(27, "Bench 27", Instant.EPOCH, Instant.EPOCH, 1, "Check the bench", null,
                new DataSet(), null, TaskStatus.CREATED, null, null, null, null, 0, null, null, null, false, null, "");
        try {
            panel.refreshModel(task);
            TaskListPanelTest.find(panel, JosmTextField.class, f -> true).setText(" Added a backrest ");
            TaskListPanelTest.find(panel, JCheckBox.class, b -> "Ask for a review".equals(b.getText()))
                    .setSelected(true);
            final var fixed = panel.feedback(task, TaskStatus.FIXED);
            assertEquals("Added a backrest", fixed.comment());
            assertEquals(Boolean.TRUE, fixed.reviewRequested());
            final var skip = panel.feedback(task, TaskStatus.SKIPPED);
            assertEquals("Added a backrest", skip.comment());
            assertNull(skip.reviewRequested());
            assertNull(skip.tags());

            panel.refreshModel(
                    new Task(28, "Bench 28", Instant.EPOCH, Instant.EPOCH, 1, "Check the bench", null, new DataSet(),
                            null, TaskStatus.CREATED, null, null, null, null, 0, null, null, null, false, null, ""));
            assertNull(panel.feedback(task, TaskStatus.FIXED).comment(), "Another task starts with no comment");
        } finally {
            panel.destroy();
        }
    }

    /**
     * A task whose review was rejected always goes back for review, as on the website (#115)
     */
    @Test
    void testRevisionRequestsReview() {
        final var rejected = new Task(27, "Bench 27", Instant.EPOCH, Instant.EPOCH, 1, "Check the bench", null,
                new DataSet(), null, TaskStatus.FIXED, null, null, null,
                new TaskReviewFields(2, null, null, null, null, null, null, null, null, null), 0, null, null, null,
                false, null, "");
        final var fresh = new Task(28, "Bench 28", Instant.EPOCH, Instant.EPOCH, 1, "Check the bench", null,
                new DataSet(), null, TaskStatus.CREATED, null, null, null, null, 0, null, null, null, false, null, "");
        assertTrue(CurrentTaskPanel.isInRevision(rejected));
        assertFalse(CurrentTaskPanel.isInRevision(fresh));
        assertEquals(Boolean.TRUE, CurrentTaskPanel.reviewRequest(rejected, TaskStatus.FIXED, false));
        assertEquals(Boolean.TRUE, CurrentTaskPanel.reviewRequest(rejected, TaskStatus.ALREADY_FIXED, false));
        assertNull(CurrentTaskPanel.reviewRequest(rejected, TaskStatus.SKIPPED, true), "A skip sets no status");
        assertNull(CurrentTaskPanel.reviewRequest(fresh, TaskStatus.FIXED, false));
        assertEquals(Boolean.TRUE, CurrentTaskPanel.reviewRequest(fresh, TaskStatus.FIXED, true));
    }

    /**
     * The panel's actions are destroyed once, so removing it does not fail
     */
    @Test
    void testDestroy() {
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(new DataSet(), "testDestroy", null));
        final var dialog = new MapRouletteDialog();
        MainApplication.getMap().addToggleDialog(dialog);
        assertDoesNotThrow(() -> MainApplication.getMap().removeToggleDialog(dialog));
        assertDoesNotThrow(() -> MainApplication.getLayerManager().resetState());
    }
}
