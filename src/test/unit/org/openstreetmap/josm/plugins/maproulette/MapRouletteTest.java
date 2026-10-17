// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import java.util.List;

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
}
