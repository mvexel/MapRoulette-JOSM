// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.GridBagLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

import org.openstreetmap.josm.data.preferences.BooleanProperty;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.Notification;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.plugins.maproulette.actions.FindChallengeAction;
import org.openstreetmap.josm.tools.GBC;

/**
 * A one-time notice on the first start after installing, pointing to the MapRoulette menu
 */
public final class FirstStartNotice {
    /**
     * {@code true} once the notice was shown
     */
    static final BooleanProperty SHOWN = new BooleanProperty("maproulette.first_start_notice.shown", false);

    private FirstStartNotice() {
        // Hide the constructor
    }

    /**
     * Show the notice once JOSM's window is up, unless it was shown before. Plugins load before the window shows, and a
     * notification needs the window.
     *
     * @param findChallenge The action for the notice's Find a challenge button
     */
    public static void showOnce(FindChallengeAction findChallenge) {
        if (SHOWN.get()) {
            return;
        }
        GuiHelper.runInEDT(() -> {
            final var mainFrame = MainApplication.getMainFrame();
            if (mainFrame == null) {
                return;
            }
            if (mainFrame.isShowing()) {
                show(findChallenge);
            } else {
                mainFrame.addWindowListener(new WindowAdapter() {
                    @Override
                    public void windowOpened(WindowEvent e) {
                        mainFrame.removeWindowListener(this);
                        show(findChallenge);
                    }
                });
            }
        });
    }

    static void show(FindChallengeAction findChallenge) {
        if (SHOWN.get()) {
            return;
        }
        SHOWN.put(true);
        final var panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        panel.add(new JLabel(tr("MapRoulette is ready. Find challenges in the new MapRoulette menu.")),
                GBC.eol().insets(0, 0, 0, 4));
        final var find = new JButton(findChallenge);
        panel.add(find, GBC.eol());
        new Notification().setContent(panel).setIcon(JOptionPane.INFORMATION_MESSAGE)
                .setDuration(Notification.TIME_VERY_LONG).show();
    }
}
