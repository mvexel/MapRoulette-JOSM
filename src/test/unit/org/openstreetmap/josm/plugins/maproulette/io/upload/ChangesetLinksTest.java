// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.io.upload;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.data.APIDataSet;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.plugins.maproulette.api.TaskAPI;
import org.openstreetmap.josm.plugins.maproulette.api.enums.TaskStatus;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedObjects;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedTask;
import org.openstreetmap.josm.plugins.maproulette.gui.TaskEdits;
import org.openstreetmap.josm.plugins.maproulette.gui.TaskResults;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteLogin;
import org.openstreetmap.josm.plugins.maproulette.util.WireMocks;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;
import org.openstreetmap.josm.testutils.annotations.Main;
import org.openstreetmap.josm.testutils.annotations.Projection;

/**
 * Changeset links survive a JOSM restart, and an upload that rolls over into a second changeset links the changeset
 * with the task's edits (#130)
 */
@BasicPreferences
@Main
@Projection
@MapRouletteConfig
class ChangesetLinksTest {
    private static final long TASK_ID = 147197178;
    private static final String TASK_PATH = "/api/v2/task/" + TASK_ID;

    @AfterEach
    void tearDown() throws Exception {
        ModifiedObjects.forgetAll();
        ChangesetLinks.forgetAll();
        LateUploadHook.forgetUnfinished();
        TaskResults.clearAll();
        sync();
        UndoRedoHandler.getInstance().clean();
    }

    /**
     * A link that waits for its changeset is kept in the preferences, and one kept by an earlier JOSM is sent
     *
     * @throws Exception if the test fails
     */
    @Test
    @MapRouletteLogin
    void testWaitingLinkSurvivesRestart() throws Exception {
        final var task = TaskAPI.get(TASK_ID);
        ChangesetLinks.await(List.of(task), 701401, server());
        assertEquals(List.of(Map.of("task", Long.toString(TASK_ID), "changeset", "701401", "server", server())),
                Config.getPref().getListOfMaps(ChangesetLinks.PREF, Collections.emptyList()).stream().map(map -> {
                    final var copy = new HashMap<>(map);
                    copy.remove("since");
                    return copy;
                }).toList());
        ChangesetLinks.forgetAll();
        assertEquals(List.of(), Config.getPref().getListOfMaps(ChangesetLinks.PREF, Collections.emptyList()));

        // As stored by a JOSM that quit before the changeset was closed
        Config.getPref().putListOfMaps(ChangesetLinks.PREF,
                Arrays.asList(new HashMap<>(Map.of("task", Long.toString(TASK_ID), "changeset", "701402", "since",
                        Instant.now().toString(), "server", server()))));
        ChangesetLinks.load();
        assertTrue(ChangesetLinks.awaits(701402));
        WireMocks.current().register(put(urlPathEqualTo(TASK_PATH + "/changeset"))
                .willReturn(aResponse().withBodyFile("api/v2/task/147197178/start")));
        ChangesetLinks.retryAll();
        assertFalse(ChangesetLinks.awaits(701402));
        WireMocks.current().verifyThat(1, putRequestedFor(urlPathEqualTo(TASK_PATH + "/changeset"))
                .withQueryParam("changesetId", equalTo("701402")));
        assertEquals(List.of(), Config.getPref().getListOfMaps(ChangesetLinks.PREF, Collections.emptyList()),
                "Sent: no longer kept");
    }

    /**
     * A stored link that can't be read is skipped
     */
    @Test
    void testBadStoredLinkSkipped() {
        Config.getPref().putListOfMaps(ChangesetLinks.PREF,
                Arrays.asList(new HashMap<>(Map.of("task", "x")), new HashMap<>(Map.of("task", Long.toString(TASK_ID),
                        "changeset", "701403", "since", Instant.now().toString(), "server", server()))));
        ChangesetLinks.load();
        assertTrue(ChangesetLinks.awaits(701403));
    }

    /**
     * An upload that fills a changeset goes on in a second one: the task is linked to the first, which holds its edit
     * and is closed already
     *
     * @throws Exception if the test fails
     */
    @Test
    @MapRouletteLogin
    void testRolloverLinksChangesetWithTaskEdits() throws Exception {
        final var task = TaskAPI.get(TASK_ID);
        final var wireMock = WireMocks.current();
        wireMock.register(
                put(urlPathEqualTo(TASK_PATH + "/" + TaskStatus.FIXED.code())).willReturn(aResponse().withStatus(200)));
        wireMock.register(get(urlPathEqualTo(TASK_PATH + "/release"))
                .willReturn(aResponse().withBodyFile("api/v2/task/147197178/start")));
        wireMock.register(put(urlPathEqualTo(TASK_PATH + "/changeset"))
                .willReturn(aResponse().withBodyFile("api/v2/task/147197178/start")));
        TaskEdits.start();
        final var dataSet = new DataSet();
        // The OSM object of task 147197178
        final var taskNode = new Node(LatLon.ZERO);
        taskNode.setOsmId(6318952236L, 1);
        dataSet.addPrimitive(taskNode);
        // Edited before Start
        final var other = new Node(new LatLon(1, 1));
        other.setOsmId(1, 1);
        dataSet.addPrimitive(other);
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(dataSet, "testRollover", null));
        UndoRedoHandler.getInstance().add(new ChangePropertyCommand(other, "amenity", "bench"));
        ModifiedObjects.addLockedTask(task);
        sync();
        UndoRedoHandler.getInstance().add(new ChangePropertyCommand(taskNode, "backrest", "yes"));
        final var hook = new LateUploadHook() {
            @Override
            boolean isChangesetClosedAfterUpload() {
                return false;
            }
        };

        ModifiedObjects.setFinishing(List.of(new ModifiedTask(task, TaskStatus.FIXED, null, null, null, Map.of())));
        GuiHelper.runInEDTAndWaitWithException(() -> {
            hook.checkUpload(new APIDataSet(dataSet));
            hook.modifyChangesetTags(new HashMap<>());
            // The task's edit filled the first changeset; the rest went into a second one, left open
            taskNode.setChangesetId(701404);
            taskNode.setModified(false);
            other.setChangesetId(701405);
            other.setModified(false);
        });
        sync();
        wireMock.verifyThat(1, putRequestedFor(urlPathEqualTo(TASK_PATH + "/" + TaskStatus.FIXED.code())));
        wireMock.verifyThat(1, putRequestedFor(urlPathEqualTo(TASK_PATH + "/changeset")).withQueryParam("changesetId",
                equalTo("701404")));
        assertFalse(ChangesetLinks.awaits(701405), "Not linked to the open changeset without the task's edit");
        wireMock.verifyThat(exactly(0), putRequestedFor(urlPathEqualTo(TASK_PATH + "/changeset"))
                .withQueryParam("changesetId", equalTo("701405")));
    }

    private static void sync() throws Exception {
        GuiHelper.runInEDTAndWait(() -> {
            /* Sync EDT */ });
        MainApplication.worker.submit(() -> {
            /* Sync worker */ }).get(10, TimeUnit.SECONDS);
        GuiHelper.runInEDTAndWait(() -> {
            /* Sync EDT */ });
    }

    private static String server() {
        return org.openstreetmap.josm.plugins.maproulette.config.MapRouletteConfig.getBaseUrl();
    }
}
