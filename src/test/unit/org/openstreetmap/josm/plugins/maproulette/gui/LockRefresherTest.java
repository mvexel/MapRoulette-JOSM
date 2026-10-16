// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteLogin;
import org.openstreetmap.josm.plugins.maproulette.util.WireMocks;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;

/**
 * Test class for {@link LockRefresher}
 */
@BasicPreferences
@MapRouletteConfig
class LockRefresherTest {
    private static Task task(long id) {
        return new Task(id, "", null, null, 15318, null, null, null, null, null, null, null, null, null, 0, null, null,
                null, false, null, null);
    }

    @Test
    @MapRouletteLogin
    void testRefreshesEveryLockedTask() {
        final var wireMock = WireMocks.current();
        wireMock.register(get(urlPathEqualTo("/api/v2/task/1/refreshLock")).willReturn(
                aResponse().withStatus(200).withBody("{\"id\":1,\"name\":\"\",\"parent\":15318,\"status\":0}")));
        // A lock someone else took over must not stop the refresh of the others
        wireMock.register(get(urlPathEqualTo("/api/v2/task/2/refreshLock"))
                .willReturn(aResponse().withStatus(403).withBody("{\"status\":\"Forbidden\",\"message\":\"no\"}")));
        wireMock.register(get(urlPathEqualTo("/api/v2/task/3/refreshLock")).willReturn(
                aResponse().withStatus(200).withBody("{\"id\":3,\"name\":\"\",\"parent\":15318,\"status\":0}")));
        ModifiedObjects.addLockedTask(task(1));
        ModifiedObjects.addLockedTask(task(2));
        ModifiedObjects.addLockedTask(task(3));
        assertDoesNotThrow(LockRefresher::refreshAll);
        for (var id : new long[] { 1, 2, 3 }) {
            wireMock.verifyThat(exactly(1), getRequestedFor(urlPathEqualTo("/api/v2/task/" + id + "/refreshLock")));
        }
    }

    /**
     * A lost lock is reported once, not at every refresh, and again only after the task is started again
     */
    @Test
    @MapRouletteLogin
    void testLostLockReportedOnce() {
        final var wireMock = WireMocks.current();
        wireMock.register(get(urlPathEqualTo("/api/v2/task/4/refreshLock"))
                .willReturn(aResponse().withStatus(403).withBody("{\"status\":\"Forbidden\",\"message\":\"no\"}")));
        final var task = task(4);
        ModifiedObjects.addLockedTask(task);
        LockRefresher.refreshAll();
        LockRefresher.refreshAll();
        wireMock.verifyThat(exactly(1), getRequestedFor(urlPathEqualTo("/api/v2/task/4/refreshLock")));
        ModifiedObjects.removeLockedTask(task);
        LockRefresher.refreshAll();
        ModifiedObjects.addLockedTask(task);
        LockRefresher.refreshAll();
        wireMock.verifyThat(exactly(2), getRequestedFor(urlPathEqualTo("/api/v2/task/4/refreshLock")));
    }

    /**
     * A task stopped and started again between two refreshes has a new lock, which is refreshed
     */
    @Test
    @MapRouletteLogin
    void testRestartedTaskIsRefreshed() {
        final var wireMock = WireMocks.current();
        wireMock.register(get(urlPathEqualTo("/api/v2/task/5/refreshLock"))
                .willReturn(aResponse().withStatus(403).withBody("{\"status\":\"Forbidden\",\"message\":\"no\"}")));
        final var task = task(5);
        ModifiedObjects.addLockedTask(task);
        LockRefresher.refreshAll();
        ModifiedObjects.removeLockedTask(task);
        ModifiedObjects.addLockedTask(task);
        LockRefresher.refreshAll();
        wireMock.verifyThat(exactly(2), getRequestedFor(urlPathEqualTo("/api/v2/task/5/refreshLock")));
    }

    /**
     * A server that does not let the login refresh locks has not taken the lock away, so it is not reported as lost
     */
    @Test
    @MapRouletteLogin
    void testUnsupportedRefreshIsNotALostLock() {
        final var wireMock = WireMocks.current();
        wireMock.register(get(urlPathEqualTo("/api/v2/task/6/refreshLock"))
                .willReturn(aResponse().withStatus(403).withBody("{\"error\":\"insufficient_scope\"}")));
        ModifiedObjects.addLockedTask(task(6));
        LockRefresher.refreshAll();
        LockRefresher.refreshAll();
        wireMock.verifyThat(exactly(2), getRequestedFor(urlPathEqualTo("/api/v2/task/6/refreshLock")));
    }

    /**
     * Without a login that may refresh locks, nothing is sent
     */
    @Test
    void testNothingWithoutLogin() {
        ModifiedObjects.addLockedTask(task(1));
        LockRefresher.refreshAll();
        WireMocks.current().verifyThat(exactly(0), anyRequestedFor(anyUrl()));
    }
}
