// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.util;

import java.net.URI;

import com.github.tomakehurst.wiremock.client.WireMock;

/**
 * Access the WireMock server started by {@link MapRouletteConfig}
 */
public final class WireMocks {
    private WireMocks() {
        // Hide constructor
    }

    /**
     * Get a client for the WireMock server backing the current MapRoulette config
     *
     * @return The client
     */
    public static WireMock current() {
        final var baseUrl = URI
                .create(org.openstreetmap.josm.plugins.maproulette.config.MapRouletteConfig.getBaseUrl());
        return new WireMock(baseUrl.getHost(), baseUrl.getPort());
    }
}
