// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui.task.current;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.plugins.maproulette.api.enums.TaskStatus;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.plugins.maproulette.gui.TaskResults;
import org.openstreetmap.josm.plugins.maproulette.gui.TaskResults.Link;
import org.openstreetmap.josm.plugins.maproulette.gui.task.current.CurrentTaskPanel.State;
import org.openstreetmap.josm.plugins.maproulette.gui.task.current.CurrentTaskPanel.Tone;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;

/**
 * Test the state line of {@link CurrentTaskPanel} (#116)
 */
@BasicPreferences
class CurrentTaskPanelStateTest {
    private static final Task TASK = new Task(235, "Bench", null, null, 10, null, null, new DataSet(), null, null, null,
            null, null, null, 0, null, null, null, false, null, null);

    private static List<String> labels(State state) {
        return state.choices().stream().map(CurrentTaskPanel.Choice::label).toList();
    }

    private static State state(boolean started, int edits, TaskResults.Result result) {
        return CurrentTaskPanel.state(TASK, started, edits, result, false, false, false, false);
    }

    /**
     * A started task says so, with its edits; with none, the line says why Upload is disabled
     */
    @Test
    void testStarted() {
        final var none = state(true, 0, null);
        assertEquals(Tone.WORKING, none.tone());
        assertEquals("Working on task 235 · no edits since it was started", none.html());
        assertEquals("Edits from before Start or from before a restart aren't counted. Already uploaded them? Use "
                + "Already fixed.", none.hint());
        assertEquals("Working on task 235 · 1 edit so far", state(true, 1, null).html());
        final var two = state(true, 2, null);
        assertEquals("Working on task 235 · 2 edits so far", two.html());
        assertNull(two.hint());
        assertEquals(List.of(), two.choices());
        // A result from before the task was started again doesn't show
        assertEquals("Working on task 235 · 2 edits so far",
                state(true, 2, new TaskResults.Result(235, TaskStatus.FIXED, 702495, Link.LINKED)).html());
    }

    /**
     * The result stays until the next Start, with the changeset linked to its page
     */
    @Test
    void testResult() {
        assertNull(state(false, 0, null));
        final var linked = state(false, 0, new TaskResults.Result(235, TaskStatus.FIXED, 702495, Link.LINKED));
        assertEquals(Tone.DONE, linked.tone());
        assertTrue(linked.html().startsWith("Task 235 marked Fixed and linked to changeset <a href=\""), linked.html());
        assertTrue(linked.html().endsWith("/changeset/702495\">702495</a>."), linked.html());
        final var failed = state(false, 0, new TaskResults.Result(235, TaskStatus.FIXED, 702495, Link.FAILED));
        assertEquals(Tone.WARNING, failed.tone());
        assertEquals("Task 235 marked Fixed. The changeset could not be linked.", failed.html());
        assertEquals("Task 235 marked Not an Issue.",
                state(false, 0, new TaskResults.Result(235, TaskStatus.FALSE_POSITIVE, 0, Link.NONE)).html());
        assertEquals("Task 235 skipped.",
                state(false, 0, new TaskResults.Result(235, TaskStatus.SKIPPED, 0, Link.NONE)).html());
    }

    /**
     * An upload that finishes the task, a Fixed that was not sent and missing objects come before the rest
     */
    @Test
    void testProblems() {
        final var uploading = CurrentTaskPanel.state(TASK, true, 2, null, true, false, false, false);
        assertEquals("Uploading. Task 235 is marked Fixed once the upload succeeds.", uploading.html());
        final var unfinished = CurrentTaskPanel.state(TASK, true, 0, null, false, true, true, false);
        assertEquals("Your edits are uploaded, but Fixed was not sent for task 235.", unfinished.html());
        assertEquals(List.of("Send again"), labels(unfinished));
        final var missing = CurrentTaskPanel.state(TASK, true, 0, null, false, false, true, false);
        assertEquals("The task's objects are not in the data.", missing.html());
        assertEquals(List.of("Download"), labels(missing));
        assertEquals("Task 235's objects could not be downloaded. They may no longer exist on OpenStreetMap.",
                CurrentTaskPanel.state(TASK, false, 0, null, false, false, true, true).html());
        // Once finished, the result matters more than the objects
        assertEquals("Task 235 marked Already fixed.",
                CurrentTaskPanel.state(TASK, false, 0,
                        new TaskResults.Result(235, TaskStatus.ALREADY_FIXED, 0, Link.NONE), false, false, true, false)
                        .html());
    }
}
