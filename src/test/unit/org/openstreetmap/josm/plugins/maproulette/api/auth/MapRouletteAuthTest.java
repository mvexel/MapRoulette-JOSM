// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.api.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;

/**
 * Test class for {@link MapRouletteAuth}
 */
@BasicPreferences
class MapRouletteAuthTest {
    @Test
    void testUnavailableByDefault() {
        assertFalse(MapRouletteAuth.isAvailable());
    }

    @Test
    void testCachedApiKeysAreDeleted() {
        final var server = "https://maproulette.org/api/v2";
        Config.getPref().put("maproulette.openstreetmap." + server + ".2078753", "2078753|secretKey");
        Config.getPref().put("maproulette.openstreetmap." + server + ".api_key", "custom_apikey");
        Config.getPref().put("maproulette.openstreetmap.https://other.example/api/v2.1", "1|otherKey");
        // ListSetting rejects List.of, which throws on contains(null)
        Config.getPref().putList("maproulette.openstreetmap.users", Arrays.asList("2078753", "1"));
        Config.getPref().put("maproulette.api.url", server);

        MapRouletteAuth.deleteCachedApiKeys();

        assertTrue(Config.getPref().getKeySet().stream().noneMatch(key -> key.startsWith("maproulette.openstreetmap.")),
                () -> "Remaining keys: " + Config.getPref().getKeySet());
        assertEquals(server, Config.getPref().get("maproulette.api.url"), "Other settings must be kept");
    }
}
