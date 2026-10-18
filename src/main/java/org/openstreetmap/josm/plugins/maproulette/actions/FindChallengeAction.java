// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.actions;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.regex.Pattern;

import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.gui.ExtendedDialog;
import org.openstreetmap.josm.gui.Notification;
import org.openstreetmap.josm.gui.widgets.JosmTextField;
import org.openstreetmap.josm.gui.widgets.UrlLabel;
import org.openstreetmap.josm.plugins.maproulette.actions.downloadtasks.MapRouletteDownloadChallengeTask;
import org.openstreetmap.josm.plugins.maproulette.gui.MRGuiHelper;
import org.openstreetmap.josm.plugins.maproulette.gui.preferences.MapRouletteServerPreference;
import org.openstreetmap.josm.tools.GBC;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * Find a challenge to work on: load one from its link or number, without leaving JOSM. When the server has a
 * website, the dialog also links to its challenge list.
 */
public class FindChallengeAction extends JosmAction {
    /**
     * A challenge in a website link, such as /browse/challenges/12 or /challenge/12/task/34
     */
    private static final Pattern CHALLENGE_LINK = Pattern.compile("/challenges?/(\\d{1,18})(?:[/?#]|$)");

    /**
     * Create a new action
     */
    public FindChallengeAction() {
        super(tr("Find a challenge…"), "dialogs/search", tr("Load a MapRoulette challenge from its link or number"),
                Shortcut.registerShortcut("maproulette:find_challenge", tr("MapRoulette: Find a challenge"),
                        KeyEvent.CHAR_UNDEFINED, Shortcut.NONE),
                false, "maproulette/find_challenge", true);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        final var input = new JosmTextField(30);
        input.setHint(tr("Challenge link or number"));
        final var panel = new JPanel(new GridBagLayout());
        panel.add(new JLabel(tr("Paste the link of a challenge, or type its number:")), GBC.eol());
        panel.add(input, GBC.eol().fill(GBC.HORIZONTAL).insets(0, 4, 0, 4));
        browseUrl().ifPresent(
                url -> panel.add(new UrlLabel(url, tr("Browse challenges on the MapRoulette website"), 2), GBC.eol()));
        final var dialog = new ExtendedDialog(MRGuiHelper.dialogParent(), tr("Find a challenge"), tr("Load challenge"),
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
        challengeId(input.getText()).ifPresentOrElse(
                id -> new MapRouletteDownloadChallengeTask().loadChallenge(id, null),
                () -> new Notification(tr("That is not a challenge link or number."))
                        .setIcon(JOptionPane.WARNING_MESSAGE).show());
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
