// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig;
import org.openstreetmap.josm.plugins.maproulette.util.WireMocks;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;

/**
 * Test class for {@link TaskAPI}
 */
@BasicPreferences
@MapRouletteConfig
class TaskAPITest {
    /**
     * {@link TaskAPI#box} must report a response it can't read instead of returning no tasks
     *
     * @param body The response body
     */
    @ParameterizedTest
    @ValueSource(strings = { "<html>Bad gateway</html>", "{\"status\": \"KO\", \"message\": \"oops\"}", "[{}]" })
    void testBoxSurfacesUnreadableResponses(String body) {
        WireMocks.current().register(
                put(urlPathMatching("/api/v2/tasks/box/.*")).willReturn(aResponse().withStatus(502).withBody(body)));
        assertThrows(IOException.class, () -> TaskAPI.box(1, 2, 3, 4, 10, 0, true, null, null, false));
    }

    /**
     * The server reads the box parameters from the query string only, and the box needs no login
     *
     * @throws IOException if the request fails
     */
    @Test
    void testBoxSendsParametersInQuery() throws IOException {
        WireMocks.current().register(put(urlPathMatching("/api/v2/tasks/box/.*"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("[]")));
        assertEquals(0, TaskAPI.box(1, 2, 3, 4, 1_000, 2, true, null, null, false).length);
        WireMocks.current()
                .verifyThat(putRequestedFor(urlPathMatching("/api/v2/tasks/box/1\\.0/2\\.0/3\\.0/4\\.0"))
                        .withQueryParam("limit", equalTo("1000")).withQueryParam("page", equalTo("2"))
                        .withQueryParam("excludeLocked", equalTo("true")).withQueryParam("includeGeometries", absent())
                        .withQueryParam("includeTags", absent()));
    }
}
