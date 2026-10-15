// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.havingExactly;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteUnsupportedException;
import org.openstreetmap.josm.plugins.maproulette.api.model.TaskBundle;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteLogin;
import org.openstreetmap.josm.plugins.maproulette.util.WireMocks;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;

/**
 * Test class for {@link BundleAPI}
 */
@BasicPreferences
@MapRouletteConfig
class BundleAPITest {
    /**
     * A bundle as MapRoulette sends it
     */
    private static final String BUNDLE = "{\"bundleId\":7,\"ownerId\":3,\"taskIds\":[1,2],\"tasks\":null}";

    @Test
    @MapRouletteLogin
    void testCreateBundle() throws Exception {
        final var wireMock = WireMocks.current();
        wireMock.register(
                post(urlPathEqualTo("/api/v2/taskBundle")).willReturn(aResponse().withStatus(201).withBody(BUNDLE)));
        final var bundle = BundleAPI.createBundle("roads", 1L, 1, 2);
        wireMock.verifyThat(postRequestedFor(urlPathEqualTo("/api/v2/taskBundle"))
                .withRequestBody(equalToJson("{\"name\":\"roads\",\"primaryId\":1,\"taskIds\":[1,2]}")));
        assertEquals(7, bundle.id());
        assertEquals(3, bundle.ownerId());
        assertArrayEquals(new long[] { 1, 2 }, bundle.taskIds());
    }

    @Test
    @MapRouletteLogin
    void testGetBundle() throws Exception {
        final var wireMock = WireMocks.current();
        wireMock.register(post(urlPathEqualTo("/api/v2/taskBundle/7")).willReturn(aResponse().withBody(BUNDLE)));
        assertEquals(7, BundleAPI.getBundle(7).id());
        wireMock.verifyThat(postRequestedFor(urlPathEqualTo("/api/v2/taskBundle/7")));
    }

    @Test
    @MapRouletteLogin
    void testUnbundleSendsEachTask() throws Exception {
        final var wireMock = WireMocks.current();
        wireMock.register(
                post(urlPathEqualTo("/api/v2/taskBundle/7/unbundle")).willReturn(aResponse().withBody(BUNDLE)));
        BundleAPI.unbundle(new TaskBundle(7, "", 3, new long[] { 1, 2, 3, 4 }, null), 3, 4);
        wireMock.verifyThat(postRequestedFor(urlPathEqualTo("/api/v2/taskBundle/7/unbundle")).withQueryParam("taskIds",
                havingExactly(equalTo("3"), equalTo("4"))));
    }

    /**
     * Servers that do not allow bundles for app logins yet answer 403 {@code insufficient_scope}
     */
    @Test
    @MapRouletteLogin
    void testUnbundleNotAllowedForLogin() {
        WireMocks.current().register(post(urlPathEqualTo("/api/v2/taskBundle/7/unbundle"))
                .willReturn(aResponse().withStatus(403).withBody("{\"error\":\"insufficient_scope\"}")));
        final var bundle = new TaskBundle(7, "", 1, new long[] { 1, 2 }, null);
        assertThrows(MapRouletteUnsupportedException.class, () -> BundleAPI.unbundle(bundle, 2));
    }
}
