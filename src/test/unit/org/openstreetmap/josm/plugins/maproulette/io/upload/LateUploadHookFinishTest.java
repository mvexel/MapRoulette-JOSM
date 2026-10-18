// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.io.upload;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.logging.LogRecord;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.plugins.maproulette.api.TaskAPI;
import org.openstreetmap.josm.plugins.maproulette.api.enums.TaskStatus;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedObjects;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedTask;
import org.openstreetmap.josm.plugins.maproulette.gui.TaskResults;
import org.openstreetmap.josm.plugins.maproulette.util.LoggingHandler;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteLogin;
import org.openstreetmap.josm.plugins.maproulette.util.WireMocks;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;

/**
 * Sending Fixed after an upload: the link waits for an open changeset, and a task another mapper took is explained
 * (#130)
 */
@BasicPreferences
@MapRouletteConfig
class LateUploadHookFinishTest {
    private static final long TASK_ID = 147197178;
    private static final String TASK_PATH = "/api/v2/task/" + TASK_ID;

    @AfterEach
    void tearDown() {
        ModifiedObjects.getModifiedTasks().forEach(ModifiedObjects::removeModifiedTask);
        ModifiedObjects.getUnlinkedFixed().forEach(ModifiedObjects::removeUnlinkedFixed);
        ModifiedObjects.getLockedTasks().forEach(ModifiedObjects::removeLockedTask);
        ChangesetLinks.forgetAll();
        LateUploadHook.forgetUnfinished();
        TaskResults.clearAll();
    }

    /**
     * MapRoulette only links a closed changeset: after an upload that leaves its changeset open, the link waits
     *
     * @throws Exception if the test fails
     */
    @Test
    @MapRouletteLogin
    void testOpenChangesetLinkWaits() throws Exception {
        final var task = TaskAPI.get(TASK_ID);
        final var wireMock = WireMocks.current();
        wireMock.register(
                put(urlPathEqualTo(TASK_PATH + "/" + TaskStatus.FIXED.code())).willReturn(aResponse().withStatus(200)));
        wireMock.register(get(urlPathEqualTo(TASK_PATH + "/release"))
                .willReturn(aResponse().withBodyFile("api/v2/task/147197178/start")));
        ModifiedObjects.addLockedTask(task);
        final var status = new ModifiedTask(task, TaskStatus.FIXED, null, null, null, Map.of());
        MainApplication.worker.submit(() -> LateUploadHook.finish(List.of(status), 701301, false, server())).get(10,
                TimeUnit.SECONDS);
        wireMock.verifyThat(putRequestedFor(urlPathEqualTo(TASK_PATH + "/" + TaskStatus.FIXED.code())));
        assertTrue(ChangesetLinks.awaits(701301), "The changeset is still open: the link waits");
        wireMock.verifyThat(exactly(0), putRequestedFor(urlPathEqualTo(TASK_PATH + "/changeset")));
    }

    /**
     * When the lock expired and another mapper started the task, the user hears why it was not marked fixed
     *
     * @param handler The log handler
     * @throws Exception if the test fails
     */
    @Test
    @MapRouletteLogin
    @LoggingHandler
    void testLockedByOtherIsExplained(LoggingHandler.TestHandler handler) throws Exception {
        final var task = TaskAPI.get(TASK_ID);
        // The error body as in LateUploadHookTest.testUnfinishedTaskCanBeSentAgain
        WireMocks.current().register(put(urlPathEqualTo(TASK_PATH + "/" + TaskStatus.FIXED.code())).willReturn(
                aResponse().withStatus(409).withBody("{\"status\":\"KO\",\"message\":\"locked_by_other\"}")));
        ModifiedObjects.addLockedTask(task);
        final var status = new ModifiedTask(task, TaskStatus.FIXED, null, null, null, Map.of());
        MainApplication.worker.submit(() -> LateUploadHook.finish(List.of(status), 701302, true, server())).get(10,
                TimeUnit.SECONDS);
        assertTrue(LateUploadHook.isUnfinished(TASK_ID));
        final var messages = handler.getRecords().stream().map(LogRecord::getMessage).filter(Objects::nonNull).toList();
        assertTrue(messages.stream().anyMatch(message -> message.startsWith(
                "Your edits are uploaded, but MapRoulette did not mark task 147197178 as fixed: another mapper has started it.")),
                messages::toString);
    }

    private static String server() {
        return org.openstreetmap.josm.plugins.maproulette.config.MapRouletteConfig.getBaseUrl();
    }
}
