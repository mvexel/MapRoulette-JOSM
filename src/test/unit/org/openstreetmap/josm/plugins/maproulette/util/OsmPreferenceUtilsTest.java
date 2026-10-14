// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.util;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.maproulette.config.MapRouletteConfig;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;

/**
 * Test class for {@link OsmPreferenceUtils}
 */
@BasicPreferences
@org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig
class OsmPreferenceUtilsTest {
    /**
     * A custom OSM preference key set in the MapRoulette server preferences must be used to look up the API key
     */
    @Test
    void testCustomOsmPreferenceKeyIsUsed() throws Exception {
        WireMocks.current().register(get(urlPathEqualTo("/api/0.6/user/preferences")).willReturn(aResponse()
                .withStatus(200)
                .withBody("<osm version=\"0.6\"><preferences>"
                        + "<preference k=\"maproulette_apikey_v2\" v=\"1|defaultKey\"/>"
                        + "<preference k=\"custom_apikey\" v=\"2|customKey\"/>" + "</preferences></osm>")));
        Config.getPref().put(MapRouletteConfig.apiKeyPreferenceKey(MapRouletteConfig.getBaseUrl()), "custom_apikey");
        assertEquals("2|customKey", OsmPreferenceUtils.getMapRouletteApiKey());
    }
}
