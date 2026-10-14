// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig;
import org.openstreetmap.josm.plugins.maproulette.util.WireMocks;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;
import org.openstreetmap.josm.testutils.annotations.Projection;

/**
 * Test class for {@link ChallengeAPI}
 */
@BasicPreferences
@MapRouletteConfig
@Projection
class ChallengeAPITest {
    private static String taskArray() throws Exception {
        return "[" + Files.readString(Path.of("src/test/resources/__files/api/v2/task/134808786/start")) + "]";
    }

    @Test
    void testRandomTaskUsesRandomTasksEndpoint() throws Exception {
        final var wireMock = WireMocks.current();
        wireMock.register(get(urlPathEqualTo("/api/v2/challenge/28467/tasks/randomTasks"))
                .willReturn(aResponse().withStatus(200).withBody(taskArray())));
        final var tasks = ChallengeAPI.randomTask(28467, null, null, 10, 134808786);
        assertEquals(1, tasks.length);
        wireMock.verifyThat(getRequestedFor(urlPathEqualTo("/api/v2/challenge/28467/tasks/randomTasks"))
                .withQueryParam("limit", equalTo("10")).withQueryParam("proximity", equalTo("134808786")));
        wireMock.verifyThat(0, getRequestedFor(urlPathEqualTo("/api/v2/challenge/28467/tasks/prioritizedTasks")));
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void testTasksNearbyExcludeSelfLocked(boolean excludeSelfLocked) throws Exception {
        final var wireMock = WireMocks.current();
        final var path = "/api/v2/challenge/28467/tasksNearby/134808786";
        wireMock.register(get(urlPathEqualTo(path)).willReturn(aResponse().withStatus(200).withBody(taskArray())));
        ChallengeAPI.tasksNearby(28467, 134808786, excludeSelfLocked, 5, 0);
        wireMock.verifyThat(getRequestedFor(urlPathEqualTo(path)).withQueryParam("excludeSelfLocked",
                excludeSelfLocked ? equalTo("true") : absent()));
    }
}
