// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.actions;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.actions.downloadtasks.DownloadParams;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.gui.ExtendedDialog;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.Notification;
import org.openstreetmap.josm.gui.progress.NullProgressMonitor;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.gui.widgets.JosmTextField;
import org.openstreetmap.josm.gui.widgets.UrlLabel;
import org.openstreetmap.josm.io.NameFinder;
import org.openstreetmap.josm.io.NameFinder.SearchResult;
import org.openstreetmap.josm.plugins.maproulette.actions.downloadtasks.MapRouletteDownloadChallengeTask;
import org.openstreetmap.josm.plugins.maproulette.actions.downloadtasks.MapRouletteDownloadTaskBox;
import org.openstreetmap.josm.plugins.maproulette.gui.MRGuiHelper;
import org.openstreetmap.josm.plugins.maproulette.gui.preferences.MapRouletteServerPreference;
import org.openstreetmap.josm.tools.GBC;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * Find a challenge to work on: load one from its link or number, or load the tasks near a place, without leaving JOSM.
 * When the server has a website, the dialog also links to its challenge list.
 */
public class FindChallengeAction extends JosmAction {
    /**
     * A challenge in a website link, such as /browse/challenges/12 or /challenge/12/task/34
     */
    private static final Pattern CHALLENGE_LINK = Pattern.compile("/challenges?/(\\d{1,18})(?:[/?#]|$)");
    /**
     * The places offered when a name matches several
     */
    private static final int MAX_PLACES = 10;

    /**
     * Create a new action
     */
    public FindChallengeAction() {
        super(tr("Find a challenge…"), "dialogs/search",
                tr("Load a MapRoulette challenge from its link or number, or the tasks near a place"),
                Shortcut.registerShortcut("maproulette:find_challenge", tr("MapRoulette: Find a challenge"),
                        KeyEvent.CHAR_UNDEFINED, Shortcut.NONE),
                false, "maproulette/find_challenge", true);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        final var input = new JosmTextField(30);
        input.setHint(tr("Challenge link or number, or a place"));
        final var panel = new JPanel(new GridBagLayout());
        panel.add(new JLabel(tr("Paste the link of a challenge, or type its number:")), GBC.eol());
        panel.add(new JLabel(tr("Or type a place, such as a city, to load the tasks near it.")), GBC.eol());
        panel.add(input, GBC.eol().fill(GBC.HORIZONTAL).insets(0, 4, 0, 4));
        browseUrl().ifPresent(
                url -> panel.add(new UrlLabel(url, tr("Browse challenges on the MapRoulette website"), 2), GBC.eol()));
        final var dialog = new ExtendedDialog(MRGuiHelper.dialogParent(), tr("Find a challenge"), tr("Find"),
                tr("Cancel")).setContent(panel, false).setButtonIcons("download", "cancel").setDefaultButton(1)
                        .setCancelButton(2);
        if (dialog.showDialog().getValue() != 1) {
            return;
        }
        final var linkHost = otherServer(input.getText(), MapRouletteServerPreference.getWebsiteUrl());
        if (linkHost.isPresent()) {
            new Notification(tr("That link is from {0}, not from the {1} server. Type the challenge number to load it "
                    + "from {1}.", linkHost.get(), MapRouletteServerPreference.getServerName()))
                            .setIcon(JOptionPane.WARNING_MESSAGE).show();
            return;
        }
        final var text = input.getText().strip();
        final var challenge = challengeId(text);
        if (challenge.isPresent()) {
            new MapRouletteDownloadChallengeTask().loadChallenge(challenge.getAsLong(), null);
        } else if (!text.isEmpty()) {
            findNear(text);
        }
    }

    /**
     * Look a place up, and load the tasks near it. A name that matches several places asks which one.
     *
     * @param place The place's name
     */
    static void findNear(String place) {
        // JOSM's own place search, as in its download dialog; it asks the Nominatim server set in JOSM
        MainApplication.worker.execute(() -> {
            final List<SearchResult> places;
            try {
                places = NameFinder.queryNominatim(place).stream().filter(p -> p.getBounds() != null).limit(MAX_PLACES)
                        .toList();
            } catch (IOException e) {
                Logging.trace(e);
                GuiHelper.runInEDT(() -> new Notification(tr("Could not look up places: {0}", e.getMessage()))
                        .setIcon(JOptionPane.WARNING_MESSAGE).show());
                return;
            }
            GuiHelper.runInEDT(() -> {
                if (places.isEmpty()) {
                    new Notification(
                            tr("No place called \"{0}\" was found, and it is not a challenge link or number.", place))
                                    .setIcon(JOptionPane.WARNING_MESSAGE).show();
                } else if (places.size() == 1) {
                    loadNear(places.get(0));
                } else {
                    choosePlace(places).ifPresent(FindChallengeAction::loadNear);
                }
            });
        });
    }

    /**
     * Ask which place was meant
     *
     * @param places The places that match the name
     * @return The chosen place
     */
    private static Optional<SearchResult> choosePlace(List<SearchResult> places) {
        final var list = new JList<>(places.stream().map(SearchResult::getName).toArray(String[]::new));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setSelectedIndex(0);
        list.setVisibleRowCount(Math.min(places.size(), 6));
        final var panel = new JPanel(new GridBagLayout());
        panel.add(new JLabel(tr("Several places have that name. Which one?")), GBC.eol());
        panel.add(new JScrollPane(list), GBC.eol().fill(GBC.HORIZONTAL).insets(0, 4, 0, 4));
        final var dialog = new ExtendedDialog(MRGuiHelper.dialogParent(), tr("Find a challenge"), tr("Load tasks here"),
                tr("Cancel")).setContent(panel, false).setButtonIcons("download", "cancel").setDefaultButton(1)
                        .setCancelButton(2);
        if (dialog.showDialog().getValue() != 1 || list.getSelectedIndex() < 0) {
            return Optional.empty();
        }
        return Optional.of(places.get(list.getSelectedIndex()));
    }

    /**
     * Load the tasks in a place's area, and show the place. Without a map, the task layer opens one.
     *
     * @param place The place
     * @return The download
     */
    static Future<?> loadNear(SearchResult place) {
        final Bounds bounds = place.getBounds();
        final var download = new MapRouletteDownloadTaskBox().download(new DownloadParams().withNewLayer(false), bounds,
                NullProgressMonitor.INSTANCE);
        // The worker runs one job at a time, so this runs after the download
        MainApplication.worker.execute(() -> GuiHelper.runInEDT(() -> {
            if (MainApplication.isDisplayingMapView()) {
                MainApplication.getMap().mapView.zoomTo(bounds);
            }
            ShowPanelAction.show();
        }));
        return download;
    }

    /**
     * Read a challenge from what the user typed
     *
     * @param text A challenge number or a link that names a challenge
     * @return The challenge id, if there is one
     */
    static OptionalLong challengeId(String text) {
        final var trimmed = text == null ? "" : text.strip();
        if (trimmed.matches("\\d{1,18}")) {
            return OptionalLong.of(Long.parseLong(trimmed));
        }
        final var matcher = CHALLENGE_LINK.matcher(trimmed);
        return matcher.find() ? OptionalLong.of(Long.parseLong(matcher.group(1))) : OptionalLong.empty();
    }

    /**
     * Check if a link is from another website than the current server's. Its challenge number would load an unrelated
     * challenge from the current server.
     *
     * @param text    What the user typed
     * @param website The current server's website, if known
     * @return The link's host if it is not the current server's website
     */
    static Optional<String> otherServer(String text, Optional<String> website) {
        final var host = host(text == null ? "" : text.strip());
        if (host.isEmpty()) {
            return Optional.empty();
        }
        return website.flatMap(FindChallengeAction::host).filter(host.get()::equalsIgnoreCase).isPresent()
                ? Optional.empty()
                : host;
    }

    private static Optional<String> host(String url) {
        try {
            return Optional.ofNullable(new URI(url).getHost());
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    /**
     * Get the challenge list on the MapRoulette website
     *
     * @return The URL, if the server has a known website
     */
    static Optional<String> browseUrl() {
        return MapRouletteServerPreference.getWebsiteUrl().map(url -> url.replaceAll("/+$", "") + "/browse/challenges");
    }
}
