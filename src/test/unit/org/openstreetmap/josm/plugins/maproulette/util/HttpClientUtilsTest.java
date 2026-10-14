// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.util;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.maproulette.api.TaskAPI;
import org.openstreetmap.josm.plugins.maproulette.api.enums.TaskStatus;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;

import com.github.tomakehurst.wiremock.client.WireMock;

/**
 * Test class for {@link HttpClientUtils}
 */
@BasicPreferences
@MapRouletteConfig
class HttpClientUtilsTest {
    @Test
    void testQueryIsEncoded() {
        final var parameters = new TreeMap<String, String>();
        parameters.put("comment", "Fixed a & b #1 = ok?");
        parameters.put("tags", "über,road");
        assertEquals("?comment=Fixed+a+%26+b+%231+%3D+ok%3F&tags=%C3%BCber%2Croad",
                HttpClientUtils.query(parameters));
    }

    @Test
    void testUpdateStatusSendsCommentIntact() throws Exception {
        final var baseUrl = URI.create(org.openstreetmap.josm.plugins.maproulette.config.MapRouletteConfig.getBaseUrl());
        final var wireMock = new WireMock(baseUrl.getHost(), baseUrl.getPort());
        wireMock.register(put(urlPathEqualTo("/api/v2/task/1/1")).willReturn(aResponse().withStatus(204)));
        assertTrue(TaskAPI.updateStatus(1, TaskStatus.FIXED, "Fixed a & b #1", "road", null, null));
        wireMock.verifyThat(putRequestedFor(urlPathEqualTo("/api/v2/task/1/1"))
                .withQueryParam("comment", equalTo("Fixed a & b #1")).withQueryParam("tags", equalTo("road")));
    }

    @Test
    void testEmptyQuery() {
        assertEquals("?", HttpClientUtils.query(Map.of()));
    }
}
