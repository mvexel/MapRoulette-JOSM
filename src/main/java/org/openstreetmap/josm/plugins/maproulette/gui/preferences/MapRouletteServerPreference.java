// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui.preferences;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.GridBagLayout;

import javax.swing.JLabel;
import javax.swing.JPanel;

import org.openstreetmap.josm.data.preferences.StringProperty;
import org.openstreetmap.josm.gui.preferences.PreferenceTabbedPane;
import org.openstreetmap.josm.gui.preferences.SubPreferenceSetting;
import org.openstreetmap.josm.gui.preferences.TabPreferenceSetting;
import org.openstreetmap.josm.gui.widgets.JosmComboBox;
import org.openstreetmap.josm.plugins.maproulette.config.MapRouletteConfig;
import org.openstreetmap.josm.tools.GBC;
import org.openstreetmap.josm.tools.Utils;

/**
 * Set the server preferences for MapRoulette
 */
public class MapRouletteServerPreference implements SubPreferenceSetting {
    /**
     * The URL to use for MapRoulette
     */
    private static final StringProperty MAPROULETTE_URL = new StringProperty("maproulette.api.url",
            "https://maproulette.org/api/v2");
    /**
     * The combobox for setting the api url
     */
    private final JosmComboBox<String> apiUrl = new JosmComboBox<>();

    @Override
    public void addGui(PreferenceTabbedPane gui) {
        final var panel = new JPanel(new GridBagLayout());
        panel.add(new JLabel(tr("MapRoulette API URL:")), GBC.std().anchor(GBC.LINE_START));
        apiUrl.addItem(MAPROULETTE_URL.get());
        if (MAPROULETTE_URL.isSet()) {
            apiUrl.addItem(MAPROULETTE_URL.getDefaultValue());
        }
        apiUrl.setSelectedItem(MAPROULETTE_URL.get());
        apiUrl.setEditable(true);
        panel.add(apiUrl, GBC.eol().fill(GBC.HORIZONTAL));
        getTabPreferenceSetting(gui).addSubTab(this, tr("Server Settings"), panel, tr("MapRoulette Server Settings"));
    }

    @Override
    public TabPreferenceSetting getTabPreferenceSetting(PreferenceTabbedPane gui) {
        return gui.getSetting(MapRoulettePreferences.class);
    }

    @Override
    public boolean ok() {
        final var newApiUrl = this.apiUrl.getText();
        if (!Utils.isStripEmpty(newApiUrl)) {
            if (newApiUrl.equals(MAPROULETTE_URL.getDefaultValue())) {
                MAPROULETTE_URL.remove();
            } else if (!newApiUrl.equals(MAPROULETTE_URL.get())) {
                MAPROULETTE_URL.put(this.apiUrl.getText());
            }
        }

        MapRouletteConfig.setInstance(new MapRouletteConfig(MAPROULETTE_URL.get()));
        return false;
    }

    @Override
    public boolean isExpert() {
        return true;
    }
}
