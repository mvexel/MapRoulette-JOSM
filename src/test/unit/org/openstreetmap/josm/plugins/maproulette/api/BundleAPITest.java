// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
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
     * Servers that do not allow bundles for app logins yet answer 403 {@code insufficient_scope}
     */
    @Test
    @MapRouletteLogin
    void testUnbundleNotAllowedForLogin() {
        WireMocks.current().register(get(urlPathEqualTo("/api/v2/taskBundle/7/unbundle"))
                .willReturn(aResponse().withStatus(403).withBody("{\"error\":\"insufficient_scope\"}")));
        final var bundle = new TaskBundle(7, "", 1, new long[] { 1, 2 }, null);
        assertThrows(MapRouletteUnsupportedException.class, () -> BundleAPI.unbundle(bundle, 2));
    }
}
