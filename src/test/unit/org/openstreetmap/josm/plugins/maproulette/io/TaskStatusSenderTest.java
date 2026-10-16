// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.io;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.swing.text.html.Option;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.TestUtils;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.plugins.maproulette.api.TaskAPI;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteHttpException;
import org.openstreetmap.josm.plugins.maproulette.api.enums.TaskStatus;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedObjects;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedTask;
import org.openstreetmap.josm.plugins.maproulette.util.ExceptionDialogUtil;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteLogin;
import org.openstreetmap.josm.plugins.maproulette.util.WireMocks;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;

import mockit.Mock;
import mockit.MockUp;

/**
 * Test class for {@link TaskStatusSender}
 */
@BasicPreferences
@MapRouletteConfig
@MapRouletteLogin
class TaskStatusSenderTest {
    @AfterEach
    void tearDown() {
        ModifiedObjects.getModifiedTasks().forEach(ModifiedObjects::removeModifiedTask);
        ModifiedObjects.getUnlinkedFixed().forEach(ModifiedObjects::removeUnlinkedFixed);
        ModifiedObjects.getLockedTasks().forEach(ModifiedObjects::removeLockedTask);
    }

    /**
     * Non-regression test: a runtime exception from MapRoulette must not reach the worker
     */
    @Test
    void testRuntimeExceptionDoesNotEscape() {
        final var task = assertDoesNotThrow(() -> TaskAPI.get(147197178));
        final var modified = new ModifiedTask(task, TaskStatus.FIXED, null, null, null, null);
        ModifiedObjects.addModifiedTask(modified);
        ModifiedObjects.addUnlinkedFixed(task);
        new MockUp<TaskAPI>() {
            @Mock
            boolean updateStatus(long task, TaskStatus status, String comment, String tags, Boolean requestReview,
                    Map<String, Option> completionResponses) {
                throw new IllegalStateException("Unexpected server response");
            }
        };
        assertDoesNotThrow(() -> TaskStatusSender.sendStatuses(List.of(modified)));
        assertDoesNotThrow(TaskStatusSenderTest::sync);
        assertTrue(ModifiedObjects.getUnlinkedFixed().isEmpty(), "A status that was not sent is not linked");
    }

    /**
     * A picked status is sent and the task released at once, as on the web (D15). Fixed with no edit is done: there
     * is nothing to link until an upload.
     *
     * @throws Exception if the test fails
     */
    @Test
    void testStatusSentAtOnce() throws Exception {
        final var task = TaskAPI.get(147197178);
        final var taskPath = "/api/v2/task/" + task.id();
        final var wireMock = WireMocks.current();
        wireMock.register(
                put(urlPathEqualTo(taskPath + "/" + TaskStatus.FIXED.code())).willReturn(aResponse().withStatus(200)));
        wireMock.register(get(urlPathEqualTo(taskPath + "/release"))
                .willReturn(aResponse().withBodyFile("api/v2/task/147197178/start")));
        final var modified = new ModifiedTask(task, TaskStatus.FIXED, null, null, null, null);
        ModifiedObjects.addLockedTask(task);
        ModifiedObjects.addModifiedTask(modified);
        ModifiedObjects.addUnlinkedFixed(task);
        final var sent = new CopyOnWriteArrayList<Map<Long, Task>>();
        final Consumer<Map<Long, Task>> listener = sent::add;
        TaskStatusSender.addListener(listener);
        try {
            MainApplication.worker.submit(() -> TaskStatusSender.sendStatuses(List.of(modified))).get(10,
                    TimeUnit.SECONDS);
            sync();
        } finally {
            TaskStatusSender.removeListener(listener);
        }
        wireMock.verifyThat(putRequestedFor(urlPathEqualTo(taskPath + "/" + TaskStatus.FIXED.code())));
        wireMock.verifyThat(getRequestedFor(urlPathEqualTo(taskPath + "/release")));
        wireMock.verifyThat(exactly(0), putRequestedFor(urlPathEqualTo(taskPath + "/changeset")));
        assertNull(ModifiedObjects.getModifiedTask(task.id()));
        assertNull(ModifiedObjects.getLockedTask(task.id()));
        assertEquals(List.of(task), ModifiedObjects.getUnlinkedFixed(), "The changeset is linked after the upload");
        assertEquals(1, sent.size());
        assertTrue(sent.get(0).containsKey(task.id()));
    }

    /**
     * A status MapRoulette rejects is not sent: the user is told why, and the task stays started so another status can
     * be picked
     *
     * @throws Exception if the test fails
     */
    @Test
    void testRejectedStatusNotSent() throws Exception {
        final var task = TaskAPI.get(147197178);
        final var taskPath = "/api/v2/task/" + task.id();
        WireMocks.current().register(
                put(urlPathEqualTo(taskPath + "/" + TaskStatus.FIXED.code())).willReturn(aResponse().withStatus(400)
                        .withBody("{\"status\":\"KO\",\"message\":\"Invalid task status supplied.\"}")));
        final var modified = new ModifiedTask(task, TaskStatus.FIXED, null, null, null, null);
        ModifiedObjects.addLockedTask(task);
        ModifiedObjects.addModifiedTask(modified);
        ModifiedObjects.addUnlinkedFixed(task);
        TestUtils.assumeWorkingJMockit();
        final var explained = new CopyOnWriteArrayList<Exception>();
        new MockUp<ExceptionDialogUtil>() {
            @Mock
            void explainException(Exception exception) {
                explained.add(exception);
            }
        };
        MainApplication.worker.submit(() -> TaskStatusSender.sendStatuses(List.of(modified))).get(10, TimeUnit.SECONDS);
        sync();
        assertNull(ModifiedObjects.getModifiedTask(task.id()));
        assertTrue(ModifiedObjects.getUnlinkedFixed().isEmpty(), "A status that was not sent is not linked");
        assertEquals(task, ModifiedObjects.getLockedTask(task.id()), "The task stays started");
        assertEquals(1, explained.size(), "The user is told");
        assertEquals("Invalid task status supplied.",
                ((MapRouletteHttpException) explained.get(0)).serverMessage().orElseThrow(),
                "with MapRoulette's reason");
    }

    /**
     * When the user switches MapRoulette servers while statuses are sent, nothing more goes to the new server, where the
     * task ids may belong to unrelated tasks (#86)
     *
     * @throws Exception if the test fails
     */
    @Test
    void testServerSwitchStopsSending() throws Exception {
        final var task = TaskAPI.get(147197178);
        final var other = TaskAPI.get(132279499);
        final var modified = new ModifiedTask(task, TaskStatus.FIXED, null, null, null, null);
        final var otherModified = new ModifiedTask(other, TaskStatus.FIXED, null, null, null, null);
        ModifiedObjects.addModifiedTask(modified);
        ModifiedObjects.addModifiedTask(otherModified);
        final var config = org.openstreetmap.josm.plugins.maproulette.config.MapRouletteConfig.getInstance();
        final var sentTo = new CopyOnWriteArrayList<Long>();
        TestUtils.assumeWorkingJMockit();
        new MockUp<TaskAPI>() {
            @Mock
            boolean updateStatus(long task, TaskStatus status, String comment, String tags, Boolean requestReview,
                    Map<String, Option> completionResponses) {
                sentTo.add(task);
                // The user switches servers while the request runs
                org.openstreetmap.josm.plugins.maproulette.config.MapRouletteConfig
                        .setInstance(new org.openstreetmap.josm.plugins.maproulette.config.MapRouletteConfig(
                                config.baseUrl() + "/x"));
                return true;
            }
        };
        try {
            MainApplication.worker.submit(() -> TaskStatusSender.sendStatuses(List.of(modified, otherModified))).get(10,
                    TimeUnit.SECONDS);
            sync();
        } finally {
            org.openstreetmap.josm.plugins.maproulette.config.MapRouletteConfig.setInstance(config);
        }
        assertEquals(List.of(task.id()), sentTo, "Only the status already in flight");
        WireMocks.current().verifyThat(exactly(0), getRequestedFor(urlPathEqualTo("/api/v2/task/147197178/release")));
    }

    /**
     * Wait for the worker and the listeners on the EDT
     *
     * @throws Exception if the test fails
     */
    private static void sync() throws Exception {
        MainApplication.worker.submit(() -> {
            /* Sync worker */ }).get(10, TimeUnit.SECONDS);
        GuiHelper.runInEDTAndWait(() -> {
            /* Sync EDT */ });
    }
}
