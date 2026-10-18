// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import javax.swing.JButton;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.gui.widgets.JMultilineLabel;
import org.openstreetmap.josm.plugins.maproulette.api.auth.AuthProvider;
import org.openstreetmap.josm.plugins.maproulette.api.auth.OAuthAuthProvider;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteClient;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;

/**
 * The login notice offers a Log in button instead of sending the user to the preferences (#118)
 */
@BasicPreferences
@MapRouletteConfig
class LoginControlTest {
    @AfterEach
    void tearDown() {
        MapRouletteClient.getInstance().setAuthProvider(AuthProvider.NONE);
    }

    /**
     * Log in buttons show only when the server accepts logins from JOSM and the user isn't logged in (#118)
     */
    @Test
    void testCanLogIn() {
        MapRouletteClient.getInstance().setAuthProvider(AuthProvider.NONE);
        assertFalse(LoginControl.canLogIn(), "The server does not accept logins from JOSM");

        final var apiUrl = org.openstreetmap.josm.plugins.maproulette.config.MapRouletteConfig.getBaseUrl();
        OAuthAuthProvider.LOGIN_SERVERS.put(Collections.singletonList(apiUrl.replaceFirst("/api/v2/?$", "")));
        final var provider = OAuthAuthProvider.forServer(apiUrl);
        assertNotNull(provider, apiUrl);
        MapRouletteClient.getInstance().setAuthProvider(provider);
        assertTrue(LoginControl.canLogIn(), "Not logged in");
    }

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
