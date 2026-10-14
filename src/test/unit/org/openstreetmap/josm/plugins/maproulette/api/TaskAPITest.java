// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;

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
        assertThrows(IOException.class, () -> TaskAPI.box(1, 2, 3, 4, 10, 0, true, null, null, false, true, true));
    }
}
