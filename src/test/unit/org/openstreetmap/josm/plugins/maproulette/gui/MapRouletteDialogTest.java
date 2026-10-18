// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Container;
import java.awt.Dimension;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.JTable;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.TestUtils;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.gui.SideButton;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.gui.widgets.UrlLabel;
import org.openstreetmap.josm.gui.widgets.JosmTextField;
import org.openstreetmap.josm.plugins.maproulette.api.enums.TaskStatus;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.plugins.maproulette.api.model.TaskReviewFields;
import org.openstreetmap.josm.plugins.maproulette.gui.layer.MapRouletteClusteredPointLayer;
import org.openstreetmap.josm.plugins.maproulette.gui.task.current.CurrentTaskPanel;
import org.openstreetmap.josm.plugins.maproulette.gui.task.list.TaskListPanel;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;
import org.openstreetmap.josm.testutils.annotations.HTTP;
import org.openstreetmap.josm.testutils.annotations.Main;
import org.openstreetmap.josm.testutils.annotations.Projection;

import mockit.Mock;
import mockit.MockUp;

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
            assertFalse(TaskListPanelTest.getFilterField(panel).getParent().isVisible(),
                    "The filters should be hidden while no tasks are loaded");
            Container ancestor = TaskListPanelTest.find(panel, JTable.class, t -> true);
            while (ancestor != panel && ancestor.isVisible()) {
                ancestor = ancestor.getParent();
            }
            assertNotSame(panel, ancestor, "The table should be hidden while no tasks are loaded");
            ancestor = TaskListPanelTest.find(panel, SideButton.class,
                    b -> b.getAction() == TaskListPanelTest.getDownloadAction(panel));
            while (ancestor != panel && ancestor.isVisible()) {
                ancestor = ancestor.getParent();
            }
            assertNotSame(panel, ancestor, "The empty state has its own buttons");
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
            final var nameLink = TaskListPanelTest.find(panel, JButton.class, l -> "Bench 27".equals(l.getText()));
            assertNotNull(nameLink, "The task name is a link that zooms to the task (#145)");
            assertNotNull(nameLink.getIcon(), "The zoom icon says the name is clickable");
            TestUtils.assumeWorkingJMockit();
            final var zoomed = new ArrayList<Task>();
            new MockUp<TaskData>() {
                @Mock
                void loadAndSelect(Task zoomedTask) {
                    zoomed.add(zoomedTask);
                }
            };
            GuiHelper.runInEDTAndWait(nameLink::doClick);
            assertEquals(List.of(task), zoomed, "Clicking the name zooms to the task");
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
     * The comment and the review request are folded behind one line, which each new task folds again (#132)
     */
    @Test
    void testFeedbackFolded() {
        final var panel = new CurrentTaskPanel();
        try {
            panel.refreshModel(
                    new Task(27, "Bench 27", Instant.EPOCH, Instant.EPOCH, 1, "Check the bench", null, new DataSet(),
                            null, TaskStatus.CREATED, null, null, null, null, 0, null, null, null, false, null, ""));
            final var toggle = TaskListPanelTest.find(panel, JButton.class,
                    b -> "\u25b8 Add a comment or ask for a review…".equals(b.getText()));
            final var comment = TaskListPanelTest.find(panel, JosmTextField.class, f -> true);
            assertTrue(toggle.isVisible());
            assertFalse(comment.getParent().isVisible(), "The fields start folded");
            final var folded = panel.neededSize();
            toggle.doClick();
            assertFalse(toggle.isVisible());
            assertEquals(folded, panel.neededSize(), "Opening the fields doesn't change the minimum");
            assertTrue(comment.getParent().isVisible());

            panel.refreshModel(
                    new Task(28, "Bench 28", Instant.EPOCH, Instant.EPOCH, 1, "Check the bench", null, new DataSet(),
                            null, TaskStatus.CREATED, null, null, null, null, 0, null, null, null, false, null, ""));
            assertTrue(toggle.isVisible(), "Another task starts folded");
            assertFalse(comment.getParent().isVisible());

            panel.refreshModel(new Task(29, "Bench 29", Instant.EPOCH, Instant.EPOCH, 1, "Check the bench", null,
                    new DataSet(), null, TaskStatus.FIXED, null, null, null,
                    new TaskReviewFields(2, null, null, null, null, null, null, null, null, null), 0, null, null, null,
                    false, null, ""));
            assertTrue(comment.getParent().isVisible(), "A rejected task shows that it goes back for review");
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
     * With no current task, the list gets the panel with a one-line notice; a started task brings the split back
     * (#132)
     */
    @Test
    void testListGetsRoomWithoutTask() {
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(new DataSet(), "testListGetsRoom", null));
        final var dialog = new MapRouletteDialog();
        MainApplication.getMap().addToggleDialog(dialog);
        try {
            assertFalse(dialog.taskList().getParent() instanceof JSplitPane);
            final var notice = TaskListPanelTest.find(dialog, JLabel.class,
                    l -> "No current task. Select a task and press Start.".equals(l.getText()));
            assertSame(dialog.taskList().getParent(), notice.getParent());
            assertThrows(AssertionError.class, () -> TaskListPanelTest.find(dialog, JSplitPane.class, p -> true),
                    "No split without a task");

            final var task = new Task(27, "Bench 27", Instant.EPOCH, Instant.EPOCH, 1, "Check the bench", null,
                    new DataSet(), null, TaskStatus.CREATED, null, null, null, null, 0, null, null, null, false, null,
                    "");
            CurrentTaskPanel.show(task, false);
            final var split = assertInstanceOf(JSplitPane.class, dialog.taskList().getParent());
            assertTrue(SwingUtilities.isDescendingFrom(dialog.currentTask(), split.getBottomComponent()));
            assertNull(notice.getParent(), "The notice goes with the split");

            dialog.currentTask().refreshModel(null);
            assertSame(notice.getParent(), dialog.taskList().getParent());
        } finally {
            MainApplication.getMap().removeToggleDialog(dialog);
        }
    }

    /**
     * The panel fits JOSM's default sidebar width, and says so when it is too small to use (#132)
     */
    @Test
    void testTooSmall() {
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(new DataSet(), "testTooSmall", null));
        final var dialog = new MapRouletteDialog();
        MainApplication.getMap().addToggleDialog(dialog);
        try {
            final var noTask = dialog.neededSize();
            assertTrue(noTask.width <= MapFrame.DEF_TOGGLE_DLG_WIDTH, noTask::toString);
            assertTrue(dialog.isTooSmall(new Dimension(noTask.width, noTask.height - 1)));
            assertTrue(dialog.isTooSmall(new Dimension(noTask.width - 1, noTask.height)));
            assertFalse(dialog.isTooSmall(noTask));

            CurrentTaskPanel.show(
                    new Task(27, "Bench 27", Instant.EPOCH, Instant.EPOCH, 1, "Check the bench", null, new DataSet(),
                            null, TaskStatus.CREATED, null, null, null, null, 0, null, null, null, false, null, ""),
                    false);
            final var withTask = dialog.neededSize();
            assertTrue(withTask.width <= MapFrame.DEF_TOGGLE_DLG_WIDTH, withTask::toString);
            assertTrue(withTask.height > noTask.height, "A task needs more room than the list alone");
            // A docked panel under JOSM's four default panels at 1600x1000 gets about 270 px, with the header line
            assertTrue(noTask.height <= 160, () -> "The list works in a short docked panel: " + noTask);
            assertTrue(withTask.height <= 160, () -> "The task works in a short docked panel: " + withTask);
            final var scroll = assertInstanceOf(JScrollPane.class,
                    SwingUtilities.getAncestorOfClass(JScrollPane.class, dialog.currentTask()));
            assertSame(scroll, SwingUtilities.getAncestorOfClass(JScrollPane.class, dialog.currentTask().getParent()));
            assertTrue(SwingUtilities.isDescendingFrom(scroll, dialog.taskList().getParent()),
                    "The current task scrolls as a whole, inside the split");
        } finally {
            MainApplication.getMap().removeToggleDialog(dialog);
        }
    }

    /**
     * The first tasks of a JOSM session open the panel; later ones leave JOSM's visibility setting alone (dry run 2)
     */
    @Test
    void testOpensForFirstTasks() {
        MapRouletteDialog.resetOpenedForTasks();
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(new DataSet(), "testOpensForFirstTasks", null));
        final var dialog = new MapRouletteDialog();
        MainApplication.getMap().addToggleDialog(dialog);
        try {
            if (dialog.isDialogShowing()) {
                dialog.hideDialog();
            }
            MainApplication.getLayerManager()
                    .addLayer(new MapRouletteClusteredPointLayer(new Bounds(40, -112, 41, -111), List.of()));
            GuiHelper.runInEDTAndWait(() -> {
                // The panel opens after the layer is in
            });
            assertTrue(dialog.isDialogShowing(), "The first tasks open the panel");

            dialog.hideDialog();
            MainApplication.getLayerManager()
                    .addLayer(new MapRouletteClusteredPointLayer(new Bounds(40, -112, 41, -111), List.of()));
            GuiHelper.runInEDTAndWait(() -> {
                // As above
            });
            assertFalse(dialog.isDialogShowing(), "Once per session: then the user's choice wins");
        } finally {
            MainApplication.getMap().removeToggleDialog(dialog);
            MapRouletteDialog.resetOpenedForTasks();
        }
    }

    /**
     * The header is one line that fits JOSM's default sidebar width, with the help as an icon (dry run 2)
     */
    @Test
    void testHeaderOneLine() {
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(new DataSet(), "testHeaderOneLine", null));
        final var dialog = new MapRouletteDialog();
        MainApplication.getMap().addToggleDialog(dialog);
        try {
            final var header = dialog.login().status().getParent();
            final var help = TaskListPanelTest.find(dialog, UrlLabel.class, l -> l.getText().contains(">Help<"));
            assertTrue(help.getToolTipText().startsWith("How it works"), help::getToolTipText);
            assertTrue(SwingUtilities.isDescendingFrom(help, header), "The help link is in the header");
            final var size = header.getPreferredSize();
            assertTrue(size.width <= MapFrame.DEF_TOGGLE_DLG_WIDTH, size::toString);
            assertTrue(size.height < 2 * header.getFontMetrics(header.getFont()).getHeight(), size::toString);
        } finally {
            MainApplication.getMap().removeToggleDialog(dialog);
        }
    }

    /**
     * In a narrow header the login text is cut short, never dropped (dry run 2, #173)
     */
    @Test
    void testHeaderKeepsLoginText() {
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(new DataSet(), "testHeaderKeepsLogin", null));
        final var dialog = new MapRouletteDialog();
        MainApplication.getMap().addToggleDialog(dialog);
        try {
            final var status = dialog.login().status();
            status.setText("Logged in as mvexelmrtest");
            final var header = status.getParent();
            // Narrower than the header wants, as in a docked panel with a long name
            final var preferred = header.getPreferredSize();
            header.setSize(preferred.width - 40, preferred.height);
            layOut(header);
            assertTrue(status.getWidth() >= 40, () -> "The login text keeps room: " + status.getBounds());
        } finally {
            MainApplication.getMap().removeToggleDialog(dialog);
        }
    }

    /**
     * In a short panel the current task scrolls as a whole and keeps a few lines of instructions; the list's buttons
     * stay below its challenge filter (dry run 2)
     */
    @Test
    void testShortPanelKeepsInstructions() {
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(new DataSet(), "testShortPanel", null));
        final var dialog = new MapRouletteDialog();
        MainApplication.getMap().addToggleDialog(dialog);
        try {
            CurrentTaskPanel.show(new Task(27, "Bench 27", Instant.EPOCH, Instant.EPOCH, 1,
                    "Check the bench.\n\nAdd backrest=yes if it has a backrest.\n\nAdd material=* if you can tell.",
                    null, new DataSet(), null, TaskStatus.CREATED, null, null, null, null, 0, null, null, null, false,
                    null, ""), false);
            final var scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class,
                    dialog.currentTask().getParent());
            final var view = (javax.swing.Scrollable) scroll.getViewport().getView();
            scroll.setSize(MapFrame.DEF_TOGGLE_DLG_WIDTH, 150);
            layOut(scroll);
            assertFalse(view.getScrollableTracksViewportHeight(), "Short: the task scrolls");
            final var line = dialog.getFontMetrics(dialog.getFont()).getHeight();
            final var instructions = TaskListPanelTest.find(dialog.currentTask(), JLabel.class,
                    l -> "Instructions: ".equals(l.getText()));
            final var inner = SwingUtilities.getAncestorOfClass(JScrollPane.class, instructions);
            assertTrue(inner.getHeight() >= instructions.getHeight() + 3 * line,
                    () -> "The instructions keep three lines: " + inner.getBounds());

            scroll.getViewport().setViewPosition(new java.awt.Point(0, 60));
            CurrentTaskPanel.show(
                    new Task(28, "Bench 28", Instant.EPOCH, Instant.EPOCH, 1, "Check the bench", null, new DataSet(),
                            null, TaskStatus.CREATED, null, null, null, null, 0, null, null, null, false, null, ""),
                    false);
            layOut(scroll);
            GuiHelper.runInEDTAndWait(() -> {
                // The scroll to the top waits for the layout
            });
            assertEquals(0, scroll.getViewport().getViewPosition().y, "Another task starts at its name");

            scroll.setSize(MapFrame.DEF_TOGGLE_DLG_WIDTH, 2000);
            layOut(scroll);
            assertTrue(view.getScrollableTracksViewportHeight(), "Tall: the task fills the view");

            final var list = dialog.taskList();
            MainApplication.getLayerManager().addLayer(new MapRouletteClusteredPointLayer(new Bounds(0, 0, 0, 0),
                    List.of(TaskListPanelTest.point(1006, 18, "SLC benches", null))));
            GuiHelper.runInEDTAndWait(() -> {
                // The list fills in
            });
            assertTrue(
                    SwingUtilities.getAncestorOfClass(JPanel.class,
                            TaskListPanelTest.find(list, javax.swing.JComboBox.class, c -> true)).isVisible(),
                    "The filters show");
            list.setSize(MapFrame.DEF_TOGGLE_DLG_WIDTH, list.squeezedSize().height);
            layOut(list);
            // The whole filters block: the challenge row and the text filter row
            final var filter = SwingUtilities.getAncestorOfClass(JPanel.class,
                    TaskListPanelTest.find(list, javax.swing.JComboBox.class, c -> true));
            final var button = TaskListPanelTest.find(list, SideButton.class, b -> true);
            final var filterBottom = SwingUtilities.convertPoint(filter, 0, filter.getHeight(), list).y;
            final var buttonTop = SwingUtilities.convertPoint(button, 0, 0, list).y;
            assertTrue(buttonTop >= filterBottom, () -> "Buttons " + buttonTop + " below the filter " + filterBottom);
        } finally {
            MainApplication.getMap().removeToggleDialog(dialog);
        }
    }

    private static void layOut(Container container) {
        container.doLayout();
        for (var child : container.getComponents()) {
            if (child instanceof Container c) {
                layOut(c);
            }
        }
    }

    /**
     * The panel asks for a fair share of the column, at least what it asked before
     */
    @Test
    void testDefaultHeight() {
        assertTrue(MapRouletteDialog.defaultHeight() >= 300);
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
