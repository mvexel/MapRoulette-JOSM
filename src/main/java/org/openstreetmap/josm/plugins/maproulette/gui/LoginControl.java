// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.GraphicsEnvironment;
import java.awt.GridBagLayout;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

import org.openstreetmap.josm.actions.PreferencesAction;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.Notification;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.gui.widgets.JMultilineLabel;
import org.openstreetmap.josm.plugins.maproulette.api.auth.MapRouletteAuth;
import org.openstreetmap.josm.plugins.maproulette.api.auth.OAuthAuthProvider;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteClient;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteException;
import org.openstreetmap.josm.plugins.maproulette.config.MapRouletteConfig;
import org.openstreetmap.josm.plugins.maproulette.gui.preferences.MapRouletteLoginPreference;
import org.openstreetmap.josm.tools.GBC;
import org.openstreetmap.josm.tools.Logging;

/**
 * A login state label and a Log in / Log out button, for the preferences and the MapRoulette panel. Use it on the EDT.
 */
public final class LoginControl {
    private final JLabel status = new JLabel();
    private final JButton button = new JButton();
    private final Runnable authListener = this::update;
    private final boolean compact;
    private volatile String error;
    private boolean listening;

    /**
     * Create a new login control
     *
     * @param compact {@code true} for short texts that fit a panel header
     */
    public LoginControl(boolean compact) {
        this.compact = compact;
        this.button.addActionListener(e -> buttonPressed());
    }

    /**
     * Get the login state label
     *
     * @return The label
     */
    public JLabel status() {
        return this.status;
    }

    /**
     * Get the Log in / Log out button
     *
     * @return The button
     */
    public JButton button() {
        return this.button;
    }

    /**
     * Start following the login state
     */
    public void attach() {
        if (!this.listening) {
            MapRouletteClient.getInstance().addAuthListener(this.authListener);
            this.listening = true;
        }
        update();
    }

    /**
     * Stop following the login state
     */
    public void detach() {
        if (this.listening) {
            MapRouletteClient.getInstance().removeAuthListener(this.authListener);
            this.listening = false;
        }
    }

    private static OAuthAuthProvider provider() {
        return MapRouletteClient.getInstance().auth()instanceof OAuthAuthProvider provider ? provider : null;
    }

    private void buttonPressed() {
        final var provider = provider();
        if (provider == null) {
            return;
        }
        if (provider.isLoggingIn()) {
            provider.cancelLogIn();
        } else if (provider.isAvailable()) {
            MainApplication.worker.execute(provider::logOut);
        } else {
            startLogIn(provider);
        }
        update();
    }

    /**
     * Log in, as the Log in button does, unless the user is logged in or logging in already. A failed login shows here.
     */
    public void logIn() {
        final var provider = provider();
        if (provider != null && !provider.isLoggingIn() && !provider.isAvailable()) {
            startLogIn(provider);
            update();
        }
    }

    /**
     * Log in from anywhere, for example a notice or a disabled button (#118). The MapRoulette panel header shows the
     * login's progress and errors; without the panel, the Login tab of the preferences opens. Call on the EDT.
     */
    public static void logInFromPanel() {
        final var dialog = MapRouletteDialog.get();
        if (dialog != null) {
            dialog.login().logIn();
        } else {
            PreferencesAction.forPreferenceSubTab(tr("Log in…"), null, MapRouletteLoginPreference.class)
                    .actionPerformed(null);
        }
    }

    /**
     * Tell the user, once per session, that a MapRoulette login is needed, with a Log in button (#118)
     */
    public static void showUnavailableNoticeOnce() {
        if (MapRouletteAuth.tellUnavailableOnce()) {
            showUnavailableNotice(Notification.TIME_VERY_LONG);
        }
    }

    /**
     * Tell the user that a MapRoulette login is needed, with a Log in button if JOSM can log in (#118). This may be
     * called from any thread; the notice is shown on the EDT.
     *
     * @param duration How long the notice shows, see {@link Notification#setDuration(int)}
     */
    public static void showUnavailableNotice(int duration) {
        final var message = MapRouletteAuth.unavailableMessage();
        if (GraphicsEnvironment.isHeadless()) {
            Logging.info(message);
            return;
        }
        GuiHelper.runInEDT(() -> new Notification().setContent(notice(message, canLogIn()))
                .setIcon(JOptionPane.INFORMATION_MESSAGE).setDuration(duration).show());
    }

    /**
     * Build the content of the login notice
     *
     * @param message  The message
     * @param canLogIn {@code true} to add a Log in button
     * @return The content
     */
    static JPanel notice(String message, boolean canLogIn) {
        final var panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        final var text = new JMultilineLabel(message);
        text.setMaxWidth(Notification.DEFAULT_CONTENT_WIDTH);
        panel.add(text, GBC.eol().fill(GBC.HORIZONTAL));
        if (canLogIn) {
            final var button = new JButton(tr("Log in…"));
            button.setToolTipText(tr("Opens MapRoulette in your browser to log in"));
            button.addActionListener(e -> logInFromPanel());
            panel.add(button, GBC.eol().anchor(GBC.LINE_START).insets(0, 4, 0, 0));
        }
        return panel;
    }

    private void startLogIn(OAuthAuthProvider provider) {
        this.error = null;
        // The login waits for the browser, so it gets its own thread instead of blocking JOSM's worker
        final var thread = new Thread(() -> logIn(provider), "MapRoulette login");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Check whether the user can log in from JOSM but isn't logged in or logging in
     *
     * @return {@code true} if a Log in button would help
     */
    public static boolean canLogIn() {
        final var provider = provider();
        return provider != null && !provider.isLoggingIn() && !provider.isAvailable();
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
            GuiHelper.runInEDT(this::update);
        }
    }

    /**
     * Show the current login state
     */
    void update() {
        final var provider = provider();
        if (provider == null) {
            final var noLogin = tr("The MapRoulette server {0} does not accept logins from JOSM.",
                    MapRouletteConfig.getBaseUrl());
            this.status.setText(this.compact ? tr("Login not available") : noLogin);
            this.status.setToolTipText(this.compact ? noLogin : null);
            this.button.setVisible(false);
            return;
        }
        this.button.setVisible(true);
        final String text;
        if (provider.isLoggingIn()) {
            text = this.compact ? tr("Waiting for the browser…")
                    : tr("Log in to MapRoulette in your browser, then come back here.");
            this.button.setText(tr("Cancel login"));
            this.button.setToolTipText(null);
        } else if (provider.isAvailable()) {
            text = this.compact ? tr("Logged in") : tr("Logged in to {0}.", provider.server());
            this.button.setText(tr("Log out"));
            this.button.setToolTipText(null);
        } else {
            text = this.compact ? tr("Not logged in")
                    : tr("Not logged in to {0}. Log in opens MapRoulette in your browser.", provider.server());
            this.button.setText(tr("Log in…"));
            this.button.setToolTipText(tr("Opens MapRoulette in your browser to log in"));
        }
        final var lastError = this.error;
        this.status.setText(lastError == null || this.compact ? text : text + ' ' + lastError);
        this.status.setToolTipText(lastError);
    }
}
