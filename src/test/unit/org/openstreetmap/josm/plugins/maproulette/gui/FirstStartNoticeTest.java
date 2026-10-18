// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Container;

import javax.swing.JButton;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;

/**
 * Test class for {@link FirstStartNotice}
 */
@BasicPreferences
class FirstStartNoticeTest {
    /**
     * The notice is due once, then never again: the preference remembers it (#110)
     */
    @Test
    void testShownOnce() {
        FirstStartNotice.SHOWN.remove();
        try {
            assertTrue(FirstStartNotice.takeTurn());
            assertTrue(FirstStartNotice.SHOWN.get());
            assertFalse(FirstStartNotice.takeTurn());
            assertFalse(FirstStartNotice.takeTurn());
        } finally {
            FirstStartNotice.SHOWN.remove();
        }
    }

    /**
     * The notice has a Find a challenge… button
     */
    @Test
    void testNotificationHasFindButton() {
        final var content = (Container) FirstStartNotice.notification().getContent();
        JButton find = null;
        for (var component : content.getComponents()) {
            if (component instanceof JButton button && "Find a challenge…".equals(button.getText())) {
                find = button;
            }
        }
        assertNotNull(find);
    }
}
