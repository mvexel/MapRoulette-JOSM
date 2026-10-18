// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;

/**
 * Test the messages of {@link ExceptionDialogUtil} that name the MapRoulette server
 */
@BasicPreferences
@MapRouletteConfig
class ExceptionDialogUtilServerTest {
    /**
     * A connection problem names the MapRoulette server, since JOSM also talks to OSM (#116)
     */
    @Test
    void testUnreachableNamesServer() {
        final var host = URI.create(org.openstreetmap.josm.plugins.maproulette.config.MapRouletteConfig.getBaseUrl())
                .getHost();
        assertEquals("Could not reach MapRoulette (" + host + "). Check your internet connection.",
                ExceptionDialogUtil.unreachable());
    }
}
