// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui.preferences;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.GridBagLayout;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;

import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.preferences.PreferenceTabbedPane;
import org.openstreetmap.josm.gui.preferences.SubPreferenceSetting;
import org.openstreetmap.josm.gui.preferences.TabPreferenceSetting;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.plugins.maproulette.api.auth.OAuthAuthProvider;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteClient;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteException;
import org.openstreetmap.josm.plugins.maproulette.config.MapRouletteConfig;
import org.openstreetmap.josm.tools.GBC;
import org.openstreetmap.josm.tools.Logging;

/**
 * Log in to and out of MapRoulette
 */
public class MapRouletteLoginPreference implements SubPreferenceSetting {
    private final JLabel status = new JLabel();
    private final JButton button = new JButton();
    private final Runnable authListener = this::update;
    /**
     * The login the user started from this panel, if it is still waiting for the browser
     */
    private volatile OAuthAuthProvider loggingIn;
    private volatile String error;
    private boolean listening;

    @Override
    public void addGui(PreferenceTabbedPane gui) {
        final var panel = new JPanel(new GridBagLayout());
        panel.add(this.status, GBC.eol().fill(GBC.HORIZONTAL));
        panel.add(this.button, GBC.eol().anchor(GBC.LINE_START));
        panel.add(new JLabel(), GBC.eol().fill(GBC.BOTH));
        this.button.addActionListener(e -> buttonPressed());
        MapRouletteClient.getInstance().addAuthListener(this.authListener);
        this.listening = true;
        update();
        getTabPreferenceSetting(gui).addSubTab(this, tr("Login"), panel, tr("Log in to MapRoulette"));
    }

    private static OAuthAuthProvider provider() {
        return MapRouletteClient.getInstance().auth()instanceof OAuthAuthProvider provider ? provider : null;
    }

    private void buttonPressed() {
        final var provider = provider();
        if (provider == null) {
            return;
        }
        if (this.loggingIn != null) {
            this.loggingIn.cancelLogIn();
        } else if (provider.isAvailable()) {
            MainApplication.worker.execute(provider::logOut);
        } else {
            this.loggingIn = provider;
            this.error = null;
            // The login waits for the browser, so it gets its own thread instead of blocking JOSM's worker
            final var thread = new Thread(() -> logIn(provider), "MapRoulette login");
            thread.setDaemon(true);
            thread.start();
        }
        update();
    }

    private void logIn(OAuthAuthProvider provider) {
        try {
            provider.logIn();
        } catch (MapRouletteException e) {
            Logging.debug(e);
            this.error = e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            this.loggingIn = null;
            GuiHelper.runInEDT(this::update);
        }
    }

    private void update() {
        final var provider = provider();
        if (provider == null) {
            this.status.setText(
                    tr("The MapRoulette server {0} does not accept logins from JOSM.", MapRouletteConfig.getBaseUrl()));
            this.button.setVisible(false);
            return;
        }
        this.button.setVisible(true);
        final String text;
        if (this.loggingIn != null) {
            text = tr("Log in to MapRoulette in your browser, then come back here.");
            this.button.setText(tr("Cancel login"));
        } else if (provider.isAvailable()) {
            text = tr("Logged in to {0}.", provider.server());
            this.button.setText(tr("Log out"));
        } else {
            text = tr("Not logged in to {0}.", provider.server());
            this.button.setText(tr("Log in"));
        }
        final var lastError = this.error;
        this.status.setText(lastError == null ? text : text + ' ' + lastError);
    }

    @Override
    public TabPreferenceSetting getTabPreferenceSetting(PreferenceTabbedPane gui) {
        return gui.getSetting(MapRoulettePreferences.class);
    }

    @Override
    public boolean ok() {
        if (this.listening) {
            MapRouletteClient.getInstance().removeAuthListener(this.authListener);
            this.listening = false;
        }
        return false;
    }

    @Override
    public boolean isExpert() {
        return false;
    }
}
