// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui.preferences;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.GraphicsEnvironment;
import java.awt.GridBagLayout;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

import org.openstreetmap.josm.data.preferences.StringProperty;
import org.openstreetmap.josm.gui.Notification;
import org.openstreetmap.josm.gui.preferences.PreferenceTabbedPane;
import org.openstreetmap.josm.gui.preferences.SubPreferenceSetting;
import org.openstreetmap.josm.gui.preferences.TabPreferenceSetting;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.gui.widgets.JosmComboBox;
import org.openstreetmap.josm.gui.widgets.JosmTextField;
import org.openstreetmap.josm.plugins.maproulette.api.auth.MapRouletteAuth;
import org.openstreetmap.josm.plugins.maproulette.config.MapRouletteConfig;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedObjects;
import org.openstreetmap.josm.plugins.maproulette.io.upload.ChangesetLinks;
import org.openstreetmap.josm.tools.GBC;
import org.openstreetmap.josm.tools.Logging;
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
     * The URL of the MapRoulette website, for links. Empty means the known website of the API server, if any (#105).
     */
    private static final StringProperty WEBSITE_URL = new StringProperty("maproulette.website.url", "");
    /**
     * The website of the default API server. Other servers have no known website: their API host is not the website.
     */
    private static final String DEFAULT_WEBSITE_URL = "https://maproulette.org";
    /**
     * The combobox for setting the api url
     */
    private final JosmComboBox<String> apiUrl = new JosmComboBox<>();
    /**
     * The field for setting the website url
     */
    private final JosmTextField websiteUrl = new JosmTextField();

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
        panel.add(new JLabel(tr("MapRoulette website URL:")), GBC.std().anchor(GBC.LINE_START));
        websiteUrl.setText(WEBSITE_URL.get());
        websiteUrl.setHint(DEFAULT_WEBSITE_URL);
        websiteUrl.setToolTipText(tr(
                "Used to open tasks on the MapRoulette website. Leave empty for maproulette.org with its default API URL."));
        panel.add(websiteUrl, GBC.eol().fill(GBC.HORIZONTAL));
        getTabPreferenceSetting(gui).addSubTab(this, tr("Server Settings"), panel, tr("MapRoulette Server Settings"));
    }

    @Override
    public TabPreferenceSetting getTabPreferenceSetting(PreferenceTabbedPane gui) {
        return gui.getSetting(MapRoulettePreferences.class);
    }

    @Override
    public boolean ok() {
        final var oldApiUrl = MAPROULETTE_URL.get();
        final var newApiUrl = this.apiUrl.getText();
        if (!Utils.isStripEmpty(newApiUrl)) {
            if (newApiUrl.equals(MAPROULETTE_URL.getDefaultValue())) {
                MAPROULETTE_URL.remove();
            } else if (!newApiUrl.equals(MAPROULETTE_URL.get())) {
                MAPROULETTE_URL.put(this.apiUrl.getText());
            }
        }

        final var newWebsiteUrl = websiteToStore(oldApiUrl, MAPROULETTE_URL.get(), WEBSITE_URL.get(),
                this.websiteUrl.getText());
        if (newWebsiteUrl.isEmpty()) {
            WEBSITE_URL.remove();
        } else {
            WEBSITE_URL.put(newWebsiteUrl);
        }

        final var oldServer = MapRouletteConfig.getInstance() == null ? null : MapRouletteConfig.getBaseUrl();
        MapRouletteConfig.setInstance(new MapRouletteConfig(MAPROULETTE_URL.get()));
        if (oldServer != null && !oldServer.equals(MapRouletteConfig.getBaseUrl())) {
            forgetTasks(oldServer);
        }
        MapRouletteAuth.configure();
        return false;
    }

    /**
     * Get the website URL to store. The website belongs to a server, so switching servers without also changing the
     * website clears it: tasks must not open on the old server's website.
     *
     * @param oldApiUrl     The API URL before the change
     * @param newApiUrl     The API URL after the change
     * @param storedWebsite The website URL before the change
     * @param typedWebsite  The website URL in the field
     * @return The website URL to store, empty to remove it
     */
    static String websiteToStore(String oldApiUrl, String newApiUrl, String storedWebsite, String typedWebsite) {
        final var typed = Utils.strip(typedWebsite);
        return !Objects.equals(oldApiUrl, newApiUrl) && typed.equals(Utils.strip(storedWebsite)) ? "" : typed;
    }

    /**
     * Forget the tasks of the server the user switched away from. Their ids may belong to unrelated tasks on the new
     * server, so nothing about them is sent there (#86).
     *
     * @param oldServer The server the user switched away from
     * @return The ids of the forgotten tasks
     */
    static Set<Long> forgetTasks(String oldServer) {
        final var ids = new TreeSet<Long>(ModifiedObjects.forgetAll());
        ids.addAll(ChangesetLinks.forgetAll());
        if (!ids.isEmpty()) {
            final var message = tr(
                    "You switched MapRoulette servers. JOSM no longer tracks tasks {0} from {1}. A task you started there "
                            + "stays started until its lock expires, and a task you marked Fixed there is not linked to "
                            + "your changeset.",
                    ids.stream().map(String::valueOf).collect(Collectors.joining(", ")), oldServer);
            Logging.info(message);
            if (!GraphicsEnvironment.isHeadless()) {
                GuiHelper.runInEDT(() -> new Notification(message).setIcon(JOptionPane.INFORMATION_MESSAGE)
                        .setDuration(Notification.TIME_LONG).show());
            }
        }
        return ids;
    }

    /**
     * Get the MapRoulette website for the current server
     *
     * @return The website URL without a trailing slash, or empty if the server has no known website
     */
    public static Optional<String> getWebsiteUrl() {
        final var url = WEBSITE_URL.get();
        if (!Utils.isStripEmpty(url)) {
            return Optional.of(Utils.strip(url).replaceAll("/+$", ""));
        }
        final var server = MapRouletteConfig.getInstance() == null ? MAPROULETTE_URL.get()
                : MapRouletteConfig.getBaseUrl();
        return MAPROULETTE_URL.getDefaultValue().equals(server) ? Optional.of(DEFAULT_WEBSITE_URL) : Optional.empty();
    }

    /**
     * Get a short name for the current server, for the panel header
     *
     * @return Production, Dev or Staging for the known servers, otherwise the host
     */
    public static String getServerName() {
        final var server = MapRouletteConfig.getInstance() == null ? MAPROULETTE_URL.get()
                : MapRouletteConfig.getBaseUrl();
        final String host;
        try {
            host = Objects.requireNonNullElse(new URI(server).getHost(), server);
        } catch (URISyntaxException e) {
            return server;
        }
        return switch (host) {
        case "maproulette.org" -> tr("Production");
        case "mr-api.osm.lol" -> tr("Dev");
        case "mr-stage.osm.lol" -> tr("Staging");
        default -> host;
        };
    }

    /**
     * Get the website URL of a task
     *
     * @param challenge The task's challenge
     * @param task      The task
     * @return The URL, or empty if the server has no known website
     */
    public static Optional<String> getTaskUrl(long challenge, long task) {
        return getWebsiteUrl().map(url -> url + "/challenge/" + challenge + "/task/" + task);
    }

    @Override
    public boolean isExpert() {
        return true;
    }
}
