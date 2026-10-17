// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.maproulette.api.TaskAPI;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteLogin;
import org.openstreetmap.josm.plugins.maproulette.util.QuittingWorker;
import org.openstreetmap.josm.plugins.maproulette.util.WireMocks;

/**
 * Test class for {@link MapRoulette}
 */
@MapRouletteConfig
class MapRouletteTest {
    /**
     * When JOSM quits, the worker is shut down before the map closes, so the started tasks are released right away
     * (B2)
     *
     * @throws Exception if the test fails
     */
    @Test
    @MapRouletteLogin
    void testReleaseLocksWhileQuitting() throws Exception {
        final var wireMock = WireMocks.current();
        wireMock.register(get(urlPathEqualTo("/api/v2/task/132279499/release"))
                .willReturn(aResponse().withBodyFile("api/v2/task/132279499/start")));
        final var task = TaskAPI.get(132279499L);
        try (var quitting = new QuittingWorker()) {
            MapRoulette.releaseLocks(List.of(task));
        }
        wireMock.verifyThat(1, getRequestedFor(urlPathEqualTo("/api/v2/task/132279499/release")));
    }

    /**
     * A server that doesn't answer doesn't hold up quitting JOSM for longer than {@link MapRoulette#RELEASE_ON_QUIT_MILLIS}
     *
     * @throws Exception if the test fails
     */
    @Test
    @MapRouletteLogin
    void testReleaseLocksWhileQuittingDoesNotWaitForSlowServer() throws Exception {
        final var wireMock = WireMocks.current();
        wireMock.register(get(urlPathEqualTo("/api/v2/task/132279499/release"))
                .willReturn(aResponse().withBodyFile("api/v2/task/132279499/start").withFixedDelay(20_000)));
        final var task = TaskAPI.get(132279499L);
        final long start = System.nanoTime();
        try (var quitting = new QuittingWorker()) {
            MapRoulette.releaseLocks(List.of(task));
        }
        final long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(waited >= MapRoulette.RELEASE_ON_QUIT_MILLIS - 100, "Waited " + waited + " ms");
        assertTrue(waited < MapRoulette.RELEASE_ON_QUIT_MILLIS + 2_000, "Waited " + waited + " ms");
    }
}
