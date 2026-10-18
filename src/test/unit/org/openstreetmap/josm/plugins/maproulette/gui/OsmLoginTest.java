// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.gui.oauth.OAuthAuthorizationWizard;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;

import mockit.Mock;
import mockit.MockUp;

/**
 * JOSM's OpenStreetMap login opens once, however often its button is clicked (#118)
 */
@BasicPreferences
class OsmLoginTest {
    @Test
    void testOneLoginAtATime() throws Exception {
        final var opened = new AtomicInteger();
        final var release = new CountDownLatch(1);
        new MockUp<OAuthAuthorizationWizard>() {
            @Mock
            void obtainAccessToken(URL serverUrl) throws InterruptedException {
                opened.incrementAndGet();
                release.await(10, TimeUnit.SECONDS);
            }
        };
        final var done = new CountDownLatch(2);
        assertTrue(OsmLogin.logIn(done::countDown));
        assertFalse(OsmLogin.logIn(done::countDown), "Already open");
        release.countDown();
        // Only the first login runs its callback
        assertFalse(done.await(2, TimeUnit.SECONDS));
        assertEquals(1, done.getCount());
        assertEquals(1, opened.get());
        assertTrue(OsmLogin.logIn(done::countDown), "Can be opened again once it ended");
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertEquals(2, opened.get());
    }
}
