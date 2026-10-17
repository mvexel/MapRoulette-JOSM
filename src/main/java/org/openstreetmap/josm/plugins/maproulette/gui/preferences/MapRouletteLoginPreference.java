// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui.preferences;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.GridBagLayout;

import javax.swing.JLabel;
import javax.swing.JPanel;

import org.openstreetmap.josm.gui.preferences.PreferenceTabbedPane;
import org.openstreetmap.josm.gui.preferences.SubPreferenceSetting;
import org.openstreetmap.josm.gui.preferences.TabPreferenceSetting;
import org.openstreetmap.josm.plugins.maproulette.gui.LoginControl;
import org.openstreetmap.josm.tools.GBC;

/**
 * Log in to and out of MapRoulette
 */
public class MapRouletteLoginPreference implements SubPreferenceSetting {
    private final LoginControl login = new LoginControl(false);

    @Override
    public void addGui(PreferenceTabbedPane gui) {
        final var panel = new JPanel(new GridBagLayout());
        panel.add(this.login.status(), GBC.eol().fill(GBC.HORIZONTAL));
        panel.add(this.login.button(), GBC.eol().anchor(GBC.LINE_START));
        panel.add(new JLabel(), GBC.eol().fill(GBC.BOTH));
        this.login.attach();
        getTabPreferenceSetting(gui).addSubTab(this, tr("Login"), panel, tr("Log in to MapRoulette"));
    }

    @Override
    public TabPreferenceSetting getTabPreferenceSetting(PreferenceTabbedPane gui) {
        return gui.getSetting(MapRoulettePreferences.class);
    }

    @Override
    public boolean ok() {
        this.login.detach();
        return false;
    }

    @Override
    public boolean isExpert() {
        return false;
    }
}
