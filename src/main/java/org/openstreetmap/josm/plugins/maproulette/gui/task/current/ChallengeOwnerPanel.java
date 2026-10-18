// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui.task.current;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.plugins.maproulette.api.UserAPI;
import org.openstreetmap.josm.plugins.maproulette.api.model.Challenge;
import org.openstreetmap.josm.plugins.maproulette.config.MapRouletteConfig;
import org.openstreetmap.josm.plugins.maproulette.gui.preferences.MapRouletteServerPreference;
import org.openstreetmap.josm.plugins.maproulette.util.Workers;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.OpenBrowser;
import org.openstreetmap.josm.tools.Utils;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * The challenge owner and how to contact them, as on the MapRoulette website (#120). The block stays hidden until the
 * owner's name is known, and when it cannot be looked up.
 */
final class ChallengeOwnerPanel extends JPanel {
    private static final String SEPARATOR = " \u00b7 ";
    /**
     * The OSM display names of challenge owners by server and OSM id, for this session. Empty if the server has no
     * usable name. The server is part of the key: an OSM id on a dev server names someone else on production.
     */
    private static final Map<String, Optional<String>> NAMES = new ConcurrentHashMap<>();
    /**
     * When a lookup last failed, or started, by the keys of {@link #NAMES}. The panel refreshes often, so a lookup is
     * asked again only after a while, and not twice at once.
     */
    private static final Map<String, Long> TRIED = new ConcurrentHashMap<>();
    /**
     * How long to wait before asking again for a name that could not be looked up
     */
    static final long RETRY_MILLIS = TimeUnit.MINUTES.toMillis(5);

    private final JLabel byLabel = new JLabel(tr("Challenge by"));
    private final JLabel ownerLink = new JLabel();
    private final JLabel messageSeparator = new JLabel(SEPARATOR);
    private final JLabel messageLink = new JLabel(link(tr("Message through OSM")));
    private final JLabel discussionSeparator = new JLabel(SEPARATOR);
    private final JLabel discussionLink = new JLabel(link(tr("Discussion")));
    /**
     * The challenge shown, or {@code null}
     */
    @Nullable
    private Challenge challenge;

    ChallengeOwnerPanel() {
        // One small line under the header, so the instructions keep their room (#168)
        super(new FlowLayout(FlowLayout.LEADING, 0, 0));
        this.discussionLink.setToolTipText(tr("Join Challenge Discussion"));
        onClick(this.ownerLink, () -> name().ifPresent(n -> OpenBrowser.displayUrl(profileUrl(n))));
        onClick(this.messageLink, () -> name().ifPresent(n -> OpenBrowser.displayUrl(messageUrl(n))));
        onClick(this.discussionLink, () -> Optional.ofNullable(this.challenge).flatMap(c -> discussionUrl(c.id()))
                .ifPresent(OpenBrowser::displayUrl));
        final var grey = UIManager.getColor("Label.disabledForeground");
        for (var label : List.of(this.byLabel, this.ownerLink, this.messageSeparator, this.messageLink,
                this.discussionSeparator, this.discussionLink)) {
            label.setFont(label.getFont().deriveFont(label.getFont().getSize2D() * 0.9f));
            if (grey != null && !label.getText().startsWith("<html>")) {
                label.setForeground(grey);
            }
            this.add(label);
        }
        this.byLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 4));
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
        final var key = key(owner);
        final var known = NAMES.get(key);
        if (known != null) {
            show(known);
            return;
        }
        this.setVisible(false);
        final var now = System.currentTimeMillis();
        final var tried = TRIED.get(key);
        if (tried != null && now - tried < RETRY_MILLIS) {
            return;
        }
        TRIED.put(key, now);
        Workers.execute(() -> {
            final var name = remember(key, owner);
            GuiHelper.runInEDT(() -> {
                final var current = this.challenge;
                if (current != null && current.general().owner() == owner) {
                    show(name);
                }
            });
        });
    }

    /**
     * Look up an owner and remember the answer. A failure to ask isn't remembered: the owner is asked again after
     * {@link #RETRY_MILLIS}.
     *
     * @param key   The key in {@link #NAMES}
     * @param owner The owner's OSM id
     * @return The name, or empty
     */
    private static Optional<String> remember(String key, long owner) {
        try {
            final var name = lookUp(owner);
            NAMES.put(key, name);
            TRIED.remove(key);
            return name;
        } catch (IOException e) {
            TRIED.put(key, System.currentTimeMillis());
            Logging.debug("MapRoulette: could not look up challenge owner " + owner + ": " + e.getMessage());
            Logging.trace(e);
            return Optional.empty();
        }
    }

    private void show(Optional<String> name) {
        name.ifPresent(n -> {
            this.ownerLink.setText(link(n));
            this.ownerLink.setToolTipText(tr("Open the OpenStreetMap profile of {0}", n));
            this.messageLink.setToolTipText(tr("Message {0} through OSM", n));
        });
        final var discussion = this.challenge != null && discussionUrl(this.challenge.id()).isPresent();
        this.discussionSeparator.setVisible(discussion);
        this.discussionLink.setVisible(discussion);
        this.setVisible(name.isPresent());
    }

    private Optional<String> name() {
        final var current = this.challenge;
        return current == null ? Optional.empty()
                : NAMES.getOrDefault(key(current.general().owner()), Optional.empty());
    }

    /**
     * Look up an owner's OSM display name through MapRoulette
     *
     * @param osmId The owner's OSM id
     * @return The name, or empty if MapRoulette has no usable name for the id
     * @throws IOException if MapRoulette could not be asked
     */
    static Optional<String> lookUp(long osmId) throws IOException {
        try {
            final var user = UserAPI.publicUser(osmId);
            // MapRoulette falls back to its own user ids, which would name someone else
            if (user.osmProfile() != null && user.osmProfile().id() == osmId && user.osmProfile().displayName() != null
                    && !user.osmProfile().displayName().isBlank()) {
                return Optional.of(user.osmProfile().displayName());
            }
        } catch (RuntimeException e) {
            Logging.debug("MapRoulette: could not read challenge owner " + osmId + ": " + e.getMessage());
            Logging.trace(e);
        }
        return Optional.empty();
    }

    private static String key(long osmId) {
        return MapRouletteConfig.getBaseUrl() + " " + osmId;
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
        // The owner's name belongs to the OSM server of the MapRoulette server, which the user also edits on: a dev
        // server's names would message someone else on openstreetmap.org (#168)
        return Config.getUrls().getOSMWebsiteDependingOnSelectedApi().replaceAll("/+$", "");
    }

    private static String encode(String name) {
        return URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * Check if the discussion link is shown, for tests
     *
     * @return {@code true} if the link is visible
     */
    boolean showsDiscussion() {
        return this.discussionLink.isVisible();
    }

    /**
     * Ask again at once for names that could not be looked up, for tests
     */
    static void retryNow() {
        TRIED.clear();
    }

    /**
     * Forget the names looked up so far, for tests
     */
    static void clearNames() {
        NAMES.clear();
        TRIED.clear();
    }

    /**
     * Get the text of a link, in the blue underlined style of the panel's other links
     *
     * @param text The plain text
     * @return The HTML text
     */
    static String link(String text) {
        return "<html><a href=\"\">" + Utils.escapeReservedCharactersHTML(text) + "</a></html>";
    }

    private static void onClick(JLabel label, Runnable action) {
        label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    action.run();
                }
            }
        });
    }
}
