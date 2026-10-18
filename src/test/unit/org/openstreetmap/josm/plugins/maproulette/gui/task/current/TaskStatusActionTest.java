// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui.task.current;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.Action;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.TestUtils;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.gui.ExtendedDialog;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.plugins.maproulette.api.enums.TaskStatus;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.plugins.maproulette.api.model.TaskClusteredPoint;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedObjects;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteLogin;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;
import org.openstreetmap.josm.testutils.annotations.Main;
import org.openstreetmap.josm.testutils.annotations.Projection;
import org.openstreetmap.josm.testutils.mockers.ExtendedDialogMocker;
import org.openstreetmap.josm.testutils.mockers.WindowMocker;

/**
 * Test class for {@link TaskStatusAction}
 */
@BasicPreferences
class TaskStatusActionTest {
    private static final Task TASK = new Task(1, "", null, null, 15318, null, null, null, null, null, null, null, null,
            null, 0, null, null, null, false, null, null);

    @Test
    @MapRouletteLogin
    void testEnabledWithLogin() {
        final var action = new TaskStatusAction(TaskStatus.ALREADY_FIXED, () -> TASK, () -> null);
        action.updateEnabledState();
        assertTrue(action.isEnabled());
    }

    /**
     * While an upload finishes the task, no other status can be picked: it would be sent after the upload, and the
     * Fixed sent for the upload would overwrite it
     */
    @Test
    @MapRouletteLogin
    void testDisabledWhileUploadFinishesTask() {
        final var action = new TaskStatusAction(TaskStatus.ALREADY_FIXED, () -> TASK, () -> null);
        ModifiedObjects.setFinishPending(List.of(TASK.id()), true);
        try {
            action.updateEnabledState();
            assertFalse(action.isEnabled());
            assertTrue(((String) action.getValue(Action.SHORT_DESCRIPTION))
                    .contains("The upload in progress finishes task 1"));
        } finally {
            ModifiedObjects.setFinishPending(List.of(TASK.id()), false);
        }
        action.updateEnabledState();
        assertTrue(action.isEnabled());
    }

    @Test
    void testDisabledWithoutLogin() {
        final var action = new TaskStatusAction(TaskStatus.ALREADY_FIXED, () -> TASK, () -> null);
        action.updateEnabledState();
        assertFalse(action.isEnabled());
        assertTrue(((String) action.getValue(Action.SHORT_DESCRIPTION)).contains("MapRoulette website"));
    }

    /**
     * An edited task is finished by uploading its edits, so Fixed is only a button for a cooperative task, whose fix it
     * applies (#115). The other buttons use the website's names.
     */
    @Test
    @MapRouletteLogin
    void testFixedOnlyForCooperativeTask() {
        final var action = new TaskStatusAction(TaskStatus.FIXED, () -> TASK, () -> null);
        action.updateEnabledState();
        assertFalse(action.isEnabled());
        assertTrue(
                ((String) action.getValue(Action.SHORT_DESCRIPTION)).contains("Upload your edits to finish the task"));
        assertEquals("Not an Issue", TaskStatusAction.label(TaskStatus.FALSE_POSITIVE));
        assertEquals("Can't Complete", TaskStatusAction.label(TaskStatus.TOO_HARD));
        assertEquals("Already fixed", TaskStatusAction.label(TaskStatus.ALREADY_FIXED));
        // The panel has no "Nothing to edit?" heading any more; the tooltips carry it
        for (var status : List.of(TaskStatus.ALREADY_FIXED, TaskStatus.FALSE_POSITIVE, TaskStatus.TOO_HARD)) {
            assertTrue(TaskStatusAction.tooltip(status).startsWith("Nothing to edit: "), status.name());
        }
        assertEquals("Put the task back for later", TaskStatusAction.tooltip(TaskStatus.SKIPPED));
        assertEquals("Skip", TaskStatusAction.label(TaskStatus.SKIPPED));
    }

    /**
     * MapRoulette refuses a status for a multiple-choice task, so the buttons are disabled with the reason (#81).
     * Skip stays open.
     */
    @Test
    @MapRouletteLogin
    void testDisabledForChoiceTask() {
        final var choiceTask = new Task(1, "", null, null, 15318, null, null, null, Task.ChoiceWork.INSTANCE, null,
                null, null, null, null, 0, null, null, null, false, null, null);
        final var action = new TaskStatusAction(TaskStatus.FIXED, () -> choiceTask, () -> null);
        action.updateEnabledState();
        assertFalse(action.isEnabled());
        assertTrue(((String) action.getValue(Action.SHORT_DESCRIPTION)).contains("Street Tally"));
        final var skip = new TaskStatusAction(TaskStatus.SKIPPED, () -> choiceTask, () -> null);
        skip.updateEnabledState();
        assertTrue(skip.isEnabled());
    }

    /**
     * A Fixed is linked to the upload that follows only if the edit layer has something to upload, so that a Fixed with
     * no edit is never linked to an unrelated upload (#97)
     */
    @Test
    @Main
    @Projection
    void testHasEditsToUpload() {
        assertFalse(TaskStatusAction.hasEditsToUpload(), "No layer");
        final var node = new Node(LatLon.ZERO);
        node.setOsmId(1, 1);
        final var dataSet = new DataSet();
        dataSet.addPrimitive(node);
        final var layer = new OsmDataLayer(dataSet, "test", null);
        MainApplication.getLayerManager().addLayer(layer);
        try {
            assertFalse(TaskStatusAction.hasEditsToUpload(), "No edit");
            node.setModified(true);
            assertTrue(TaskStatusAction.hasEditsToUpload());
        } finally {
            MainApplication.getLayerManager().removeLayer(layer);
        }
    }

    /**
     * Only a deliberate multi-select that holds the current task offers to mark the other selected tasks. A stray
     * highlighted row never does (#104).
     */
    @Test
    void testOthersToMark() {
        final var other = task(2);
        final var third = task(3);
        assertEquals(List.of(), TaskStatusAction.othersToMark(List.of(other), TASK), "Only another row selected");
        assertEquals(List.of(), TaskStatusAction.othersToMark(List.of(other, third), TASK),
                "Other rows without the current task");
        assertEquals(List.of(), TaskStatusAction.othersToMark(List.of(TASK), TASK), "Only the current task");
        assertEquals(List.of(), TaskStatusAction.othersToMark(List.of(other), null), "No current task");
        assertEquals(List.of(other, third), TaskStatusAction.othersToMark(List.of(TASK, other, third), TASK),
                "Current task and others");
    }

    /**
     * Marking the other selected tasks needs an explicit Yes: No is the default and the cancel button, and only the
     * first button marks them (#104)
     *
     * @throws ReflectiveOperationException if the dialog fields can't be read
     */
    @Test
    void testMarkAllDialogDefaultsToNo() throws ReflectiveOperationException {
        TestUtils.assumeWorkingJMockit();
        new WindowMocker();
        final var action = new TaskStatusAction(TaskStatus.FIXED, () -> TASK, () -> null);
        final List<TaskClusteredPoint> others = List.of(task(2), task(3));
        final var dialog = action.markAllDialog(others);
        assertEquals(2, (int) TestUtils.getPrivateField(dialog, "defaultButtonIdx"), "No is the default");
        final var content = "<html>Also mark the other selected tasks (2, 3) as Fixed?<br>"
                + "This applies their tag fixes and geometry fixes.</html>";
        final var answers = new HashMap<String, Object>(Map.of(content, "Only the current task"));
        new ExtendedDialogMocker(answers);
        assertFalse(action.confirmMarkAll(others), "No marks only the current task");
        answers.put(content, "Mark them too");
        assertTrue(action.confirmMarkAll(others), "Yes marks the others too");
        answers.put(content, ExtendedDialog.DialogClosedOtherwise);
        assertFalse(action.confirmMarkAll(others), "Closing the dialog marks only the current task");
    }

    private static Task task(long id) {
        return new Task(id, "", null, null, 15318, null, null, null, null, null, null, null, null, null, 0, null, null,
                null, false, null, null);
    }
}
