// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui.task.current;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.Cursor;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.plugins.maproulette.api.UserAPI;
import org.openstreetmap.josm.plugins.maproulette.api.model.Challenge;
import org.openstreetmap.josm.plugins.maproulette.gui.preferences.MapRouletteServerPreference;
import org.openstreetmap.josm.plugins.maproulette.util.Workers;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.GBC;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.OpenBrowser;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * The challenge owner and how to contact them, as on the MapRoulette website (#120). The block stays hidden until the
 * owner's name is known, and when it cannot be looked up.
 */
final class ChallengeOwnerPanel extends JPanel {
    /**
     * The OSM display names of challenge owners by OSM id, for this session. Empty if the lookup failed.
     */
    private static final Map<Long, Optional<String>> NAMES = new ConcurrentHashMap<>();

    private final JLabel ownerLabel = new JLabel(tr("Owner:"));
    private final JButton ownerLink = link();
    private final JButton messageLink = link();
    private final JButton discussionLink = link();
    /**
     * The challenge shown, or {@code null}
     */
    @Nullable
    private Challenge challenge;

    ChallengeOwnerPanel() {
        super(new GridBagLayout());
        this.ownerLink.setToolTipText(tr("Open the owner''s OpenStreetMap profile"));
        this.discussionLink.setText(tr("Join Challenge Discussion"));
        this.discussionLink.setToolTipText(tr("Open the challenge''s conversation on the MapRoulette website"));
        this.ownerLink.addActionListener(e -> name().ifPresent(n -> OpenBrowser.displayUrl(profileUrl(n))));
        this.messageLink.addActionListener(e -> name().ifPresent(n -> OpenBrowser.displayUrl(messageUrl(n))));
        this.discussionLink.addActionListener(e -> Optional.ofNullable(this.challenge)
                .flatMap(c -> discussionUrl(c.id())).ifPresent(OpenBrowser::displayUrl));
        this.add(this.ownerLabel, GBC.std().insets(0, 0, 4, 0));
        this.add(this.ownerLink, GBC.eol().fill(GBC.HORIZONTAL));
        this.add(this.messageLink, GBC.eol().fill(GBC.HORIZONTAL));
        this.add(this.discussionLink, GBC.eol().fill(GBC.HORIZONTAL));
        this.setVisible(false);
    }

    /**
     * Show the owner of a challenge. The name is looked up in the background the first time. Call on the EDT.
     *
     * @param newChallenge The challenge of the current task, or {@code null} if it is not known yet
     */
    void setChallenge(@Nullable Challenge newChallenge) {
        this.challenge = newChallenge;
        if (newChallenge == null || newChallenge.general().owner() <= 0) {
            this.setVisible(false);
            return;
        }
        final long owner = newChallenge.general().owner();
        final var known = NAMES.get(owner);
        if (known != null) {
            show(known);
            return;
        }
        this.setVisible(false);
        Workers.execute(() -> {
            final var name = lookUp(owner);
            NAMES.put(owner, name);
            GuiHelper.runInEDT(() -> {
                final var current = this.challenge;
                if (current != null && current.general().owner() == owner) {
                    show(name);
                }
            });
        });
    }

    private void show(Optional<String> name) {
        name.ifPresent(n -> {
            this.ownerLink.setText(n);
            this.messageLink.setText(tr("Message {0} through OSM", n));
        });
        this.discussionLink
                .setVisible(this.challenge != null && discussionUrl(this.challenge.id()).isPresent());
        this.setVisible(name.isPresent());
    }

    private Optional<String> name() {
        final var current = this.challenge;
        return current == null ? Optional.empty()
                : NAMES.getOrDefault(current.general().owner(), Optional.empty());
    }

    /**
     * Look up an owner's OSM display name through MapRoulette
     *
     * @param osmId The owner's OSM id
     * @return The name, or empty if it could not be looked up
     */
    static Optional<String> lookUp(long osmId) {
        try {
            final var user = UserAPI.publicUser(osmId);
            // MapRoulette falls back to its own user ids, which would name someone else
            if (user.osmProfile() != null && user.osmProfile().id() == osmId
                    && user.osmProfile().displayName() != null && !user.osmProfile().displayName().isBlank()) {
                return Optional.of(user.osmProfile().displayName());
            }
        } catch (IOException | RuntimeException e) {
            Logging.debug("MapRoulette: could not look up challenge owner " + osmId + ": " + e.getMessage());
            Logging.trace(e);
        }
        return Optional.empty();
    }

    /**
     * Get the OSM profile of a user, on the OpenStreetMap website JOSM uses
     *
     * @param name The OSM display name
     * @return The URL
     */
    static String profileUrl(@Nonnull String name) {
        return osmWebsite() + "/user/" + encode(name);
    }

    /**
     * Get the page that writes a message to a user on OpenStreetMap
     *
     * @param name The OSM display name
     * @return The URL
     */
    static String messageUrl(@Nonnull String name) {
        return osmWebsite() + "/message/new/" + encode(name);
    }

    /**
     * Get the conversation tab of a challenge on the MapRoulette website
     *
     * @param challengeId The challenge
     * @return The URL, or empty if the server has no known website
     */
    static Optional<String> discussionUrl(long challengeId) {
        return MapRouletteServerPreference.getWebsiteUrl()
                .map(url -> url + "/browse/challenges/" + challengeId + "?tab=conversation");
    }

    private static String osmWebsite() {
        return Config.getUrls().getOSMWebsite().replaceAll("/+$", "");
    }

    private static String encode(String name) {
        return URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * Forget the names looked up so far, for tests
     */
    static void clearNames() {
        NAMES.clear();
    }

    private static JButton link() {
        final var button = new JButton();
        // A link-like button, as the task name in the header
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setHorizontalAlignment(SwingConstants.LEADING);
        button.setMargin(new Insets(0, 0, 0, 0));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }
}
