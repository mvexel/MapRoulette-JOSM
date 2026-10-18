// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.actions;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.Cursor;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;

import javax.swing.JButton;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.gui.widgets.UrlLabel;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.OpenBrowser;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * Open the help page that explains MapRoulette in JOSM: the terms and the steps
 */
public class HowItWorksAction extends JosmAction {
    /**
     * The help page. The draft for now (#119); this is the only place to change when it moves.
     */
    public static final String HELP_URL = "https://wiki.openstreetmap.org/wiki/User:Mvexel/MapRoulette";

    /**
     * Create a new action
     */
    public HowItWorksAction() {
        super(tr("How MapRoulette in JOSM works"), "help", tr("Open the MapRoulette help page in your browser"),
                Shortcut.registerShortcut("maproulette:how_it_works", tr("MapRoulette: How it works"),
                        KeyEvent.CHAR_UNDEFINED, Shortcut.NONE),
                false, "maproulette/how_it_works", true);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        OpenBrowser.displayUrl(HELP_URL);
    }

    /**
     * Get a "How it works" link to the help page, for the panel
     *
     * @return The link
     */
    public static UrlLabel link() {
        return new UrlLabel(HELP_URL, tr("How it works"), 2);
    }

    /**
     * Get a small help button that opens the help page, for the one-line panel header
     *
     * @return The button
     */
    public static JButton button() {
        final var button = new JButton(ImageProvider.get("help", ImageProvider.ImageSizes.SMALLICON));
        button.setToolTipText(tr("How it works: open the MapRoulette help page in your browser"));
        button.getAccessibleContext().setAccessibleName(tr("How it works"));
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setMargin(new Insets(0, 0, 0, 0));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.addActionListener(e -> OpenBrowser.displayUrl(HELP_URL));
        return button;
    }
}
