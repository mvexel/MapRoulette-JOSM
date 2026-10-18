// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.GraphicsEnvironment;
import java.awt.GridBagLayout;

import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import org.openstreetmap.josm.data.preferences.BooleanProperty;
import org.openstreetmap.josm.gui.Notification;
import org.openstreetmap.josm.gui.widgets.JMultilineLabel;
import org.openstreetmap.josm.plugins.maproulette.actions.FindChallengeAction;
import org.openstreetmap.josm.tools.GBC;

/**
 * Point a new user to the MapRoulette menu, once: on the first start after installing the plugin, never again (#110)
 */
public final class FirstStartNotice {
    /**
     * Set once the notice was shown
     */
    static final BooleanProperty SHOWN = new BooleanProperty("maproulette.first_start_notice.shown", false);

    private FirstStartNotice() {
        // Hide the constructor
    }

    /**
     * Show the notice if it was never shown, and remember that it was
     */
    public static void showOnce() {
        if (!takeTurn() || GraphicsEnvironment.isHeadless()) {
            return;
        }
        // JOSM loads plugins before it shows its window, and queues that on the EDT: show the notice after it
        SwingUtilities.invokeLater(() -> SwingUtilities.invokeLater(() -> notification().show()));
    }

    /**
     * Check whether the notice is due, and mark it shown
     *
     * @return {@code true} the first time only
     */
    static synchronized boolean takeTurn() {
        if (SHOWN.get()) {
            return false;
        }
        SHOWN.put(true);
        return true;
    }

    /**
     * Build the notice
     *
     * @return The notification, with a Find a challenge… button
     */
    static Notification notification() {
        final var content = new JPanel(new GridBagLayout());
        content.setOpaque(false);
        final var text = new JMultilineLabel(tr("MapRoulette is ready. Find challenges in the new MapRoulette menu."));
        text.setMaxWidth(Notification.DEFAULT_CONTENT_WIDTH);
        content.add(text, GBC.eol().fill(GBC.HORIZONTAL));
        content.add(new JButton(new FindChallengeAction()), GBC.eol().anchor(GBC.LINE_START).insets(0, 6, 0, 0));
        return new Notification().setContent(content).setIcon(JOptionPane.INFORMATION_MESSAGE)
                .setDuration(Notification.TIME_VERY_LONG);
    }
}
