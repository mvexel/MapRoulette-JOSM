// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import javax.swing.JButton;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.gui.widgets.JMultilineLabel;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig;

/**
 * The login notice offers a Log in button instead of sending the user to the preferences (#118)
 */
@MapRouletteConfig
class LoginControlTest {
    @Test
    void testNoticeHasLogIn() {
        final var notice = LoginControl.notice("Log in to MapRoulette.", true);
        final var text = Arrays.stream(notice.getComponents()).filter(JMultilineLabel.class::isInstance)
                .map(JMultilineLabel.class::cast).findFirst().orElseThrow();
        assertTrue(text.getText().contains("Log in to MapRoulette."), text.getText());
        final var button = Arrays.stream(notice.getComponents()).filter(JButton.class::isInstance)
                .map(JButton.class::cast).findFirst().orElseThrow();
        assertEquals("Log in…", button.getText());
        assertTrue(button.getToolTipText().contains("browser"), "Says that a browser window opens");
    }

    @Test
    void testNoticeWithoutLogIn() {
        final var notice = LoginControl.notice("MapRoulette does not accept logins from JOSM.", false);
        assertFalse(Arrays.stream(notice.getComponents()).anyMatch(JButton.class::isInstance),
                "No button when JOSM can't log in");
    }
}
