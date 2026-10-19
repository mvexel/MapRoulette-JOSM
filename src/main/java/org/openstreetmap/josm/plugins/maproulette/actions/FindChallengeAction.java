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
import java.util.function.Consumer;
import java.util.regex.Pattern;

import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.actions.downloadtasks.DownloadParams;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.osm.BBox;
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
import org.openstreetmap.josm.plugins.maproulette.gui.layer.MapRouletteClusteredPointLayer;
import org.openstreetmap.josm.plugins.maproulette.gui.preferences.MapRouletteServerPreference;
import org.openstreetmap.josm.tools.GBC;
import org.openstreetmap.josm.tools.ImageProvider;
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
     * The largest place loaded at once, in degrees: a large county or metro area fits, a state doesn't
     */
    static final double MAX_PLACE_DEGREES = 2;

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
        ask("", null);
    }

    /**
     * Ask for a challenge or a place, and load it
     *
     * @param text    The text to start with
     * @param problem What went wrong with the text, shown above it, if anything
     */
    static void ask(String text, String problem) {
        final var input = new JosmTextField(30);
        input.setText(text);
        final var panel = askPanel(input, problem);
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
        final var typed = input.getText().strip();
        final var challenge = challengeId(typed);
        if (challenge.isPresent()) {
            new MapRouletteDownloadChallengeTask().loadChallenge(challenge.getAsLong(), null);
        } else if (!typed.isEmpty()) {
            findNear(typed, found -> ask(typed, found));
        }
    }

    /**
     * Build the content of the dialog that asks for a challenge or a place
     *
     * @param input   The text field
     * @param problem What went wrong with the text, shown above it, if anything
     * @return The content
     */
    static JPanel askPanel(JosmTextField input, String problem) {
        input.setHint(tr("Challenge link or number, or a place"));
        final var panel = new JPanel(new GridBagLayout());
        panel.add(new JLabel(tr("Paste the link of a challenge, or type its number:")), GBC.eol());
        panel.add(new JLabel(tr("Or type a place, such as Omaha, Nebraska, to load the tasks near it.")), GBC.eol());
        if (problem != null) {
            final var label = new JLabel(problem, ImageProvider.get("warning-small"), SwingConstants.LEADING);
            panel.add(label, GBC.eol().insets(0, 4, 0, 0));
        }
        panel.add(input, GBC.eol().fill(GBC.HORIZONTAL).insets(0, 4, 0, 4));
        browseUrl().ifPresent(
                url -> panel.add(new UrlLabel(url, tr("Browse challenges on the MapRoulette website"), 2), GBC.eol()));
        return panel;
    }

    /**
     * Look a place up, and load the tasks near it. A name that matches several places asks which one.
     *
     * @param place     The place's name
     * @param onProblem Called on the EDT with what to tell the user when nothing can be loaded
     */
    static void findNear(String place, Consumer<String> onProblem) {
        // JOSM's own place search, as in its download dialog; it asks the Nominatim server set in JOSM
        MainApplication.worker.execute(() -> {
            final List<SearchResult> places;
            try {
                places = NameFinder.queryNominatim(place).stream().filter(p -> p.getBounds() != null).limit(MAX_PLACES)
                        .toList();
            } catch (IOException e) {
                Logging.debug("Find a challenge: no place search for {0}: {1}", place, e.getMessage());
                Logging.trace(e);
                GuiHelper.runInEDT(() -> onProblem
                        .accept(tr("Couldn''t look up places right now. A challenge number or link still works.")));
                return;
            }
            GuiHelper.runInEDT(() -> {
                if (places.isEmpty()) {
                    onProblem.accept(tr("Nothing found for \"{0}\". Type a challenge number or link, or a place such "
                            + "as Omaha, Nebraska.", place));
                } else if (places.size() == 1) {
                    loadNear(places.get(0), onProblem);
                } else {
                    choosePlace(places).ifPresent(chosen -> loadNear(chosen, onProblem));
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
        // The full names (city, county, state, country) tell places with the same name apart
        final var list = new JList<>(places.stream().map(SearchResult::getName).toArray(String[]::new));
        final var panel = choosePanel(list);
        final var dialog = new ExtendedDialog(MRGuiHelper.dialogParent(), tr("Find a challenge"), tr("Load tasks"),
                tr("Cancel")).setContent(panel, false).setButtonIcons("download", "cancel").setDefaultButton(1)
                        .setCancelButton(2);
        if (dialog.showDialog().getValue() != 1 || list.getSelectedIndex() < 0) {
            return Optional.empty();
        }
        return Optional.of(places.get(list.getSelectedIndex()));
    }

    /**
     * Build the content of the dialog that asks which place was meant
     *
     * @param list The places' names
     * @return The content
     */
    static JPanel choosePanel(JList<String> list) {
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setSelectedIndex(0);
        list.setVisibleRowCount(Math.min(list.getModel().getSize(), 6));
        final var panel = new JPanel(new GridBagLayout());
        panel.add(new JLabel(tr("Several places have that name. Which one?")), GBC.eol());
        panel.add(new JScrollPane(list), GBC.eol().fill(GBC.HORIZONTAL).insets(0, 4, 0, 4));
        return panel;
    }

    /**
     * Load the tasks in a place's area, and show the place. Without a map, the task layer opens one.
     *
     * @param place     The place
     * @param onProblem Called on the EDT when the place is too big to load
     */
    static void loadNear(SearchResult place, Consumer<String> onProblem) {
        final Bounds bounds = place.getBounds();
        final var name = place.getName().split(",", 2)[0].strip();
        if (bounds.getHeight() > MAX_PLACE_DEGREES || bounds.getWidth() > MAX_PLACE_DEGREES) {
            // The box download stops at 1000 tasks: a state or a country would load partway, in no clear order
            onProblem.accept(tr("{0} is too big to load at once. Type a city or county instead.", name));
            return;
        }
        new MapRouletteDownloadTaskBox().download(new DownloadParams().withNewLayer(false), bounds,
                NullProgressMonitor.INSTANCE);
        // The worker runs one job at a time, so this runs after the download
        MainApplication.worker.execute(() -> GuiHelper.runInEDT(() -> {
            if (MainApplication.isDisplayingMapView()) {
                MainApplication.getMap().mapView.zoomTo(bounds);
            }
            ShowPanelAction.show();
            final var area = new BBox(bounds.getMinLon(), bounds.getMinLat(), bounds.getMaxLon(), bounds.getMaxLat());
            final var found = MainApplication.getLayerManager().getLayersOfType(MapRouletteClusteredPointLayer.class)
                    .stream().flatMap(layer -> layer.getTasks().stream()).anyMatch(t -> area.intersects(t.getBBox()));
            if (!found) {
                new Notification(tr("No MapRoulette tasks near {0}. Try a nearby city, or ask the challenge owner for "
                        + "the challenge number.", name)).setIcon(JOptionPane.INFORMATION_MESSAGE).show();
            }
        }));
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
