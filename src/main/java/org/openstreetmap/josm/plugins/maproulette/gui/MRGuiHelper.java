// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.Component;
import java.awt.Dialog;
import java.awt.GridLayout;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.swing.JComponent;
import javax.swing.JPanel;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.plugins.maproulette.api_caching.ChallengeCache;
import org.openstreetmap.josm.plugins.maproulette.markdown.SelectParser;
import org.openstreetmap.josm.tools.Utils;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * A helper class for gui rendering
 */
public final class MRGuiHelper {
    /**
     * Replace mustache variables (see <a href="https://learn.maproulette.org/documentation/mustache-tag-replacement/">
     *     MapRoulette mustache tag replacement
     * </a>)
     */
    private static final Pattern MUSTACHE_PATTERN = Pattern.compile("\\{{2}(.*?)}{2}");
    /**
     * Keys that should not be found in OSM but are often present in task metadata
     */
    private static final Set<String> DISCARDABLE_KEYS = Set.of(
            // Common MR tags used for identifiers
            "id", "@id", "osmid", "osm_id", "osmIdentifier",
            // Atlas checks
            "flag:check", "flag:generator", "identifier", "itemType", "last_edit_changeset", "last_edit_time",
            "last_edit_user_id", "last_edit_version");

    private MRGuiHelper() {
        // Hide constructor
    }

    /**
     * Get the instruction text for a task
     *
     * @param currentTask The task to get instructiohns for
     * @return The instruction text
     */
    @Nonnull
    public static String getInstructionText(@Nullable Task currentTask) {
        if (currentTask == null) {
            return "";
        }
        final String instruction;
        if (Utils.isStripEmpty(currentTask.instruction())) {
            // Called on the EDT, so only use a cached challenge; a missing one is fetched in the background
            final var challenge = ChallengeCache.cached(currentTask.parentId()).orElse(null);
            if (challenge == null) {
                instruction = tr("The instructions from the challenge are not loaded yet");
            } else if (!Utils.isStripEmpty(challenge.general().instruction())) {
                instruction = challenge.general().instruction();
            } else if (!Utils.isStripEmpty(challenge.description())) {
                instruction = challenge.description();
            } else {
                instruction = challenge.name();
            }
        } else {
            instruction = currentTask.instruction();
        }
        final var matcher = MUSTACHE_PATTERN.matcher(instruction);
        final var builder = new StringBuilder(instruction.length());
        while (matcher.find()) {
            final var tag = matcher.group(1);
            final var replacement = currentTask.geometries().allPrimitives().stream().map(prim -> prim.get(tag))
                    .filter(Objects::nonNull).collect(Collectors.joining(System.lineSeparator()));
            matcher.appendReplacement(builder,
                    Matcher.quoteReplacement(Utils.isStripEmpty(replacement) ? matcher.group(0) : replacement));
        }
        matcher.appendTail(builder);

        // Instructions can be markdown, so we want to convert it to html
        final var selectParser = Collections.singletonList(new SelectParser());
        final var node = Parser.builder().extensions(selectParser).build().parse(builder.toString());
        return "<html>" + HtmlRenderer.builder().extensions(selectParser).build().render(node) + "</html>";
    }

    /**
     * Filter keys (to remove keys that are from challenge objects)
     *
     * @param keys The keys to filter
     * @return The filtered stream
     */
    public static Stream<String> filterKeys(Stream<String> keys) {
        return keys.filter(key -> !DISCARDABLE_KEYS.contains(key));
    }

    /**
     * Get the window to parent a dialog to. The MapRoulette panels can be detached windows above the main frame, so a
     * dialog parented to the main frame can open behind them.
     *
     * @return The focused window if there is one, else the main frame
     */
    public static Component dialogParent() {
        return dialogParent(KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow());
    }

    /**
     * Get the window to parent a dialog to
     *
     * @param active The active window, if any
     * @return The active window if it is showing and not modal, else the main frame
     */
    static Component dialogParent(@Nullable Window active) {
        // A modal window is usually about to close (such as the PleaseWaitDialog of a running job), and it would take
        // the dialog with it
        return active != null && active.isShowing() && !(active instanceof Dialog dialog && dialog.isModal()) ? active
                : MainApplication.getMainFrame();
    }

    /**
     * Lay out buttons in rows of equal width buttons, like the buttons of a {@link
     * org.openstreetmap.josm.gui.dialogs.ToggleDialog}
     *
     * @param rows The buttons of each row, usually {@link org.openstreetmap.josm.gui.SideButton}s
     * @return The panel with the rows
     */
    public static JPanel buttonRows(List<? extends List<? extends JComponent>> rows) {
        final var panel = new JPanel(new GridLayout(rows.size(), 1));
        for (var row : rows) {
            final var rowPanel = new JPanel(new GridLayout(1, row.size()));
            row.forEach(rowPanel::add);
            panel.add(rowPanel);
        }
        return panel;
    }
}
