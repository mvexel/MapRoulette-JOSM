// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.gui.Notification;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.plugins.maproulette.actions.FindChallengeAction;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;
import org.openstreetmap.josm.testutils.annotations.Main;

import mockit.Mock;
import mockit.MockUp;

/**
 * Test class for {@link FirstStartNotice}
 */
@BasicPreferences
@Main
class FirstStartNoticeTest {
    /**
     * The notice shows once, and never again after that (#110)
     */
    @Test
    void testShowsOnce() {
        final var shown = new AtomicInteger();
        new MockUp<Notification>() {
            @Mock
            public void show() {
                shown.incrementAndGet();
            }
        };
        FirstStartNotice.SHOWN.put(false);
        final var findChallenge = new FindChallengeAction();
        FirstStartNotice.show(findChallenge);
        assertEquals(1, shown.get());
        assertTrue(FirstStartNotice.SHOWN.get());
        // Shown before: neither the next start nor a second call shows it
        FirstStartNotice.show(findChallenge);
        FirstStartNotice.showOnce(findChallenge);
        GuiHelper.runInEDTAndWait(() -> {
            // Let any queued show run
        });
        assertEquals(1, shown.get());
    }
}
