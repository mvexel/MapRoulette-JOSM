// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui.task.current;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import java.awt.Component;
import java.awt.Container;

import javax.swing.JButton;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.plugins.maproulette.api.enums.TaskStatus;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.plugins.maproulette.gui.TaskListPanelTest;
import org.openstreetmap.josm.plugins.maproulette.gui.TaskResults;
import org.openstreetmap.josm.plugins.maproulette.gui.TaskResults.Link;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig;
import org.openstreetmap.josm.plugins.maproulette.util.WireMocks;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.testutils.annotations.Main;
import org.openstreetmap.josm.testutils.annotations.Projection;

import mockit.Invocation;
import mockit.Mock;
import mockit.MockUp;

/**
 * Test {@link NextTaskPanel}: the next task after a finished one, and the tasks done this session (#117)
 */
@Main
@MapRouletteConfig
@Projection
class NextTaskPanelTest {
    /**
     * Task 241 of dev challenge 11, finished
     */
    private static final Task FINISHED = task(241, 11);

    private static Task task(long id, long challenge) {
        return new Task(id, "node/" + id, null, null, challenge, null, null, new DataSet(), null, null, null, null,
                null, null, 0, null, null, null, false, null, null);
    }

    @AfterEach
    void tearDown() {
        TaskResults.clearAll();
    }

    /**
     * The first task MapRoulette offers that isn't excluded
     */
    @Test
    void testPick() {
        final var tasks = new Task[] { task(1, 11), task(2, 11), task(3, 11) };
        assertEquals(1, NextTaskPanel.pick(tasks, Set.of()).id());
        assertEquals(3, NextTaskPanel.pick(tasks, Set.of(1L, 2L)).id());
        assertNull(NextTaskPanel.pick(tasks, Set.of(1L, 2L, 3L)));
        assertNull(NextTaskPanel.pick(new Task[0], Set.of()));
    }

    /**
     * The done this session list names the outcome, with the changeset as a link once it is linked
     */
    @Test
    void testOutcome() {
        assertEquals("Skipped", NextTaskPanel.outcomeHtml(new TaskResults.Result(1, TaskStatus.SKIPPED, 0, Link.NONE)));
        assertEquals("Already fixed",
                NextTaskPanel.outcomeHtml(new TaskResults.Result(1, TaskStatus.ALREADY_FIXED, 0, Link.NONE)));
        assertEquals("Fixed · not linked",
                NextTaskPanel.outcomeHtml(new TaskResults.Result(1, TaskStatus.FIXED, 701802, Link.FAILED)));
        assertEquals(
                "Fixed · <a href=\"" + Config.getUrls().getBaseBrowseUrl() + "/changeset/701802\">changeset 701802</a>",
                NextTaskPanel.outcomeHtml(new TaskResults.Result(1, TaskStatus.FIXED, 701802, Link.LINKED)));
        assertEquals("Picked by the challenge's priorities · task 151", NextTaskPanel.detail(151, false));
        assertEquals("Nearest open task · task 151", NextTaskPanel.detail(151, true));
    }

    /**
     * After task 241, MapRoulette offers 242 and 241. The finished task and the tasks done this session are skipped,
     * and Pick another goes round the offered tasks again.
     */
    @Test
    void testOffersNextTask() throws Exception {
        final var wireMock = WireMocks.current();
        final var path = "/api/v2/challenge/11/tasks/prioritizedTasks";
        wireMock.register(get(urlPathEqualTo(path)).willReturn(aResponse().withStatus(200).withBody(
                Files.readString(Path.of("src/test/resources/__files/api/v2/challenge/11/tasks/prioritizedTasks")))));
        final var panel = GuiHelper.runInEDTAndWaitAndReturn(NextTaskPanel::new);
        try {
            GuiHelper.runInEDTAndWait(() -> panel.show(FINISHED, false));
            sync();
            assertEquals(242, panel.candidate().id());
            assertFalse(GuiHelper.runInEDTAndWaitAndReturn(panel::start), "No task list to start it from");
            final var pick = TaskListPanelTest.find(panel, JButton.class, b -> "Pick another".equals(b.getText()));
            // 241 is the finished task: Pick another asks again, and starts over with 242
            GuiHelper.runInEDTAndWait(pick::doClick);
            sync();
            assertEquals(242, panel.candidate().id());
            wireMock.verifyThat(2, getRequestedFor(urlPathEqualTo(path)));

            // Done this session: no longer offered
            TaskResults.sent(task(242, 11), TaskStatus.SKIPPED);
            GuiHelper.runInEDTAndWait(pick::doClick);
            sync();
            assertNull(panel.candidate());
            assertFalse(GuiHelper.runInEDTAndWaitAndReturn(panel::start), "Nothing offered");
            assertEquals(List.of(242L), TaskResults.session().stream().map(done -> done.task().id()).toList());
            assertTrue(TaskListPanelTest.find(panel, JButton.class, b -> "Try again".equals(b.getText())).isEnabled());
        } finally {
            panel.destroy();
        }
    }

    /**
     * The offered task starts on its own only when asked: not when it arrives with auto-start off
     *
     * @throws Exception if the test fails
     */
    @Test
    void testStartsOnlyWhenAsked() throws Exception {
        WireMocks.current()
                .register(get(urlPathEqualTo("/api/v2/challenge/11/tasks/prioritizedTasks"))
                        .willReturn(aResponse().withStatus(200).withBody(Files.readString(
                                Path.of("src/test/resources/__files/api/v2/challenge/11/tasks/prioritizedTasks")))));
        final var starts = new ArrayList<Long>();
        new MockUp<NextTaskPanel>() {
            @Mock
            boolean start(Invocation invocation) {
                starts.add(invocation.<NextTaskPanel> getInvokedInstance().candidate().id());
                return true;
            }
        };
        final var panel = GuiHelper.runInEDTAndWaitAndReturn(NextTaskPanel::new);
        try {
            GuiHelper.runInEDTAndWait(() -> panel.show(FINISHED, false));
            sync();
            assertEquals(242, panel.candidate().id());
            assertTrue(starts.isEmpty(), "Auto-start off");
            GuiHelper.runInEDTAndWait(() -> panel.show(task(243, 11), true));
            sync();
            assertEquals(List.of(242L), starts, "Auto-start on: starts once the answer arrives");
        } finally {
            panel.destroy();
        }
    }

    /**
     * In the docked panel the card fits its width, also with a long task name: wider, it was cut off on both sides
     * (dry run 2)
     */
    @Test
    void testFitsDockedWidth() {
        final var longName = new Task(244, "A bench with a very long name on the corner of Main Street and 4th Avenue",
                null, null, 11, null, null, new DataSet(), null, null, null, null, null, null, 0, null, null, null,
                false, null, null);
        final var panel = GuiHelper.runInEDTAndWaitAndReturn(NextTaskPanel::new);
        try {
            GuiHelper.runInEDTAndWait(() -> {
                TaskResults.sent(longName, TaskStatus.SKIPPED);
                panel.refreshDone();
            });
            final var scroll = new JScrollPane(panel, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                    ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            // The docked column, less the current task's own scroll bar
            scroll.setSize(MapFrame.DEF_TOGGLE_DLG_WIDTH - 20, 400);
            GuiHelper.runInEDTAndWait(() -> layOut(scroll));
            final var width = scroll.getViewport().getWidth();
            assertEquals(width, panel.getWidth(), "The card takes the panel's width");
            assertInside(panel, panel, width);
        } finally {
            panel.destroy();
        }
    }

    private static void assertInside(NextTaskPanel panel, Container container, int width) {
        for (Component child : container.getComponents()) {
            if (!child.isVisible() || child.getWidth() == 0) {
                continue;
            }
            final var left = SwingUtilities.convertPoint(child, 0, 0, panel).x;
            assertTrue(left >= 0 && left + child.getWidth() <= width, () -> child.getClass().getSimpleName() + " at "
                    + left + ".." + (left + child.getWidth()) + " is inside 0.." + width);
            if (child instanceof Container c) {
                assertInside(panel, c, width);
            }
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

    private static void sync() throws Exception {
        MainApplication.worker.submit(() -> {
            /* Sync worker */ }).get();
        GuiHelper.runInEDTAndWait(() -> {
            /* Sync EDT */ });
    }
}
