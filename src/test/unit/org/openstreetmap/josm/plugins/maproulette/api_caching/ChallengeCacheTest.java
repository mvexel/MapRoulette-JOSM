// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.api_caching;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.plugins.maproulette.api.auth.AuthProvider;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteClient;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig;
import org.openstreetmap.josm.plugins.maproulette.util.WireMocks;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;
import org.openstreetmap.josm.tools.HttpClient;

/**
 * Test class for {@link ChallengeCache}
 */
@BasicPreferences
@MapRouletteConfig
class ChallengeCacheTest {
    /**
     * {@link ChallengeCache#isHidden(long)} is used while painting and sorting, so it must not block on the network
     * (#33). It fetches an unknown challenge on the worker and notifies listeners on the EDT.
     *
     * @throws Exception if the test fails
     */
    @Test
    void testIsHiddenFetchesInBackground() throws Exception {
        final long id = 990_033L;
        final var body = Files
                .readString(Paths.get("src/test/resources/__files/api/v2/challenge/15318"), StandardCharsets.UTF_8)
                .replace("\"deleted\": false", "\"deleted\": true");
        WireMocks.current().register(get(urlPathEqualTo("/api/v2/challenge/" + id))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)));
        final var originalAuth = MapRouletteClient.getInstance().auth();
        final var onEdt = new CopyOnWriteArrayList<Boolean>();
        MapRouletteClient.getInstance().setAuthProvider(new AuthProvider() {
            @Override
            public boolean isAvailable() {
                return false;
            }

            @Override
            public Set<Capability> capabilities() {
                return EnumSet.noneOf(Capability.class);
            }

            @Override
            public void authenticate(HttpClient request) {
                onEdt.add(SwingUtilities.isEventDispatchThread());
            }
        });
        final var loaded = new CountDownLatch(1);
        final Runnable listener = () -> {
            assertTrue(SwingUtilities.isEventDispatchThread());
            loaded.countDown();
        };
        ChallengeCache.addListener(listener);
        try {
            final var hidden = new boolean[1];
            GuiHelper.runInEDTAndWaitWithException(() -> hidden[0] = ChallengeCache.isHidden(id));
            assertFalse(hidden[0], "An unknown challenge is shown until it arrives");
            assertTrue(loaded.await(10, TimeUnit.SECONDS), "Listeners are told when the challenge arrives");
            assertTrue(ChallengeCache.isHidden(id));
            assertEquals(List.of(false), onEdt);
        } finally {
            ChallengeCache.removeListener(listener);
            MapRouletteClient.getInstance().setAuthProvider(originalAuth);
        }
    }
}
