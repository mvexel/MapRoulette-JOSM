// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
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
}
