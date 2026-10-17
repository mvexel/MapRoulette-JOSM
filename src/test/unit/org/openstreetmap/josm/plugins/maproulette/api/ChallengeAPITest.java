// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
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

    /**
     * The open tasks come from the task box of the whole map, limited to the challenge, without a login. /challenge/view
     * answers with a GeoJSON export and /challenge/clustered fails with HTTP 500 (#134).
     */
    @Test
    void testOpenTasks() throws Exception {
        final var wireMock = WireMocks.current();
        final var path = "/api/v2" + ChallengeAPI.WORLD_BOX;
        wireMock.register(put(urlPathEqualTo(path)).willReturn(aResponse().withStatus(200).withBody(Files.readString(
                Path.of("src/test/resources/__files/api/v2/tasks/box/-119.1139411/36.0770518/-119.0992458/36.0839216")))));
        final var tasks = ChallengeAPI.openTasks(15318);
        assertEquals(4, tasks.length);
        wireMock.verifyThat(1,
                putRequestedFor(urlPathEqualTo(path)).withQueryParam("cid", equalTo("15318"))
                        .withQueryParam("tStatus", equalTo("0")).withQueryParam("excludeLocked", equalTo("true"))
                        .withQueryParam("limit", equalTo(Integer.toString(ChallengeAPI.TASK_LIMIT))));
    }

    /**
     * Tasks that are done are left out, even if the server sends them (#134)
     *
     * @throws Exception if the test fails
     */
    @Test
    void testOpenTasksOnlyCreated() throws Exception {
        final var body = Files
                .readString(Path.of(
                        "src/test/resources/__files/api/v2/tasks/box/-119.1139411/36.0770518/-119.0992458/36.0839216"))
                .replaceFirst("\"status\": 0", "\"status\": 1");
        WireMocks.current().register(put(urlPathEqualTo("/api/v2" + ChallengeAPI.WORLD_BOX))
                .willReturn(aResponse().withStatus(200).withBody(body)));
        assertEquals(3, ChallengeAPI.openTasks(15318).length);
    }

    /**
     * A response in another shape, like the GeoJSON export of /challenge/view, is an IOException the download task
     * reports, never a runtime exception that opens the bug report dialog (#134)
     */
    @Test
    void testOpenTasksRejectsGeoJson() {
        WireMocks.current().register(put(urlPathEqualTo("/api/v2/tasks/box/-180/-85/180/85"))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {"type": "FeatureCollection", "features": [{"type": "Feature",
                          "geometry": {"type": "Point", "coordinates": [1, 2]},
                          "properties": {"mr_taskId": 1, "mr_taskStatus": "Created"}}]}
                        """)));
        assertThrows(IOException.class, () -> ChallengeAPI.openTasks(15318));
    }
}
