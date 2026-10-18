// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.util;

import static org.openstreetmap.josm.gui.help.HelpUtil.ht;
import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

import javax.swing.JOptionPane;

import org.openstreetmap.josm.gui.Notification;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.plugins.maproulette.api.auth.MapRouletteAuth;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteAuthUnavailableException;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteException;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteForbiddenException;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteHttpException;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteLockHeldException;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteNotFoundException;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteParseException;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteUnsupportedException;
import org.openstreetmap.josm.plugins.maproulette.api.enums.TaskStatus;
import org.openstreetmap.josm.tools.ExceptionUtil;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Utils;

/**
 * This is a more generic class for explaining exceptions, see {@link org.openstreetmap.josm.gui.ExceptionDialogUtil}.
 * Known problems show as a JOSM Notification: none of them needs a decision, so they don't stop the user with a
 * dialog (#116).
 */
public final class ExceptionDialogUtil {
    /**
     * A task request path, with what it does to the task
     */
    private static final Pattern TASK_PATH = Pattern.compile("/task/\\d+/([A-Za-z0-9]+)$");

    private ExceptionDialogUtil() {
        // Hide cosntructor
    }

    /**
     * Explain exceptions. This may be called from any thread; the notification is shown on the EDT.
     * @param exception The exception to explain to the user
     */
    public static void explainException(Exception exception) {
        GuiHelper.runInEDT(() -> explainExceptionOnEdt(exception));
    }

    private static void explainExceptionOnEdt(Exception exception) {
        if (exception.getClass() == MapRouletteException.class && exception.getCause()instanceof IOException cause) {
            // A connection problem: explain the underlying exception
            explainExceptionOnEdt(cause);
        } else if (exception instanceof MapRouletteAuthUnavailableException) {
            notify(MapRouletteAuth.unavailableMessage(), JOptionPane.INFORMATION_MESSAGE, null);
        } else if (exception instanceof MapRouletteLockHeldException lockHeld) {
            if (lockHeld.heldTaskId().isPresent()) {
                showError(tr("You already have MapRoulette task {0} started. MapRoulette allows one started task"
                        + " at a time; stop that task first.", Long.toString(lockHeld.heldTaskId().getAsLong())),
                        null);
            } else {
                // MapRoulette also answers 409 for conflicts that are not about locks, such as OSM change conflicts
                showError(lockHeld.serverMessage().map(m -> tr("MapRoulette reported a conflict: {0}", m))
                        .orElseGet(() -> tr("MapRoulette reported a conflict.")), null);
            }
        } else if (exception instanceof MapRouletteUnsupportedException unsupported) {
            showError(explainRefusal(unsupported), null);
        } else if (exception instanceof MapRouletteForbiddenException) {
            showError(tr("MapRoulette did not allow this. Another mapper may be working on the task."), null);
        } else if (exception instanceof MapRouletteNotFoundException) {
            showError(tr("MapRoulette does not have this task or challenge (any more)."), null);
        } else if (exception instanceof MapRouletteHttpException httpException && httpException.status() == 400) {
            // MapRoulette's message says why, for example a status change it does not allow or a paused challenge
            showError(httpException.serverMessage().map(m -> tr("MapRoulette did not accept this: {0}", m))
                    .orElseGet(() -> tr("MapRoulette did not accept this.")), null);
        } else if (exception instanceof MapRouletteParseException) {
            showError(tr("Could not read the MapRoulette response: {0}", exception.getMessage()), null);
        } else if (exception instanceof SocketException socketException) {
            showError(tr("Could not connect to the server: {0}. Please check your internet connection.",
                    socketException.getMessage()), ht("/ErrorMessages#NestedSocketException"));
        } else if (exception instanceof UnknownHostException unknownHostException) {
            showError(tr("Unknown server {0}. Please check your internet connection.",
                    unknownHostException.getMessage()), ht("/ErrorMessages#UnknownHost"));
        } else if (exception instanceof IOException) {
            // JOSM's explanation is HTML already
            notify(ExceptionUtil.explainException(exception), JOptionPane.WARNING_MESSAGE,
                    ht("/ErrorMessages#NestedIOException"));
        } else {
            org.openstreetmap.josm.gui.ExceptionDialogUtil.explainException(exception);
        }
    }

    /**
     * Explain why MapRoulette refused a request from the JOSM login, naming the refused action when it is known
     *
     * @param exception The refusal
     * @return The explanation
     */
    public static String explainRefusal(MapRouletteUnsupportedException exception) {
        final var action = exception.path() == null ? null : refusedAction(exception.path());
        if (exception.reason() == MapRouletteUnsupportedException.Reason.INSUFFICIENT_SCOPE && action != null) {
            return tr("This MapRoulette server does not allow JOSM to {0}.", action);
        }
        return explainRefusal(exception.reason());
    }

    /**
     * Describe the action of a MapRoulette request for the user
     *
     * @param path The request path
     * @return The action, or {@code null} if it is not a known one
     */
    static String refusedAction(String path) {
        final var matcher = TASK_PATH.matcher(path);
        if (path.contains("/taskBundle")) {
            return tr("work on task bundles");
        } else if (!matcher.find()) {
            return null;
        }
        final var action = matcher.group(1);
        if (action.chars().allMatch(Character::isDigit)) {
            try {
                return tr("mark a task as {0}", TaskStatus.fromCode(Integer.parseInt(action)).description());
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return switch (action) {
        case "start" -> tr("start a task");
        case "release" -> tr("stop a task");
        case "refreshLock" -> tr("keep a task started");
        case "skip" -> tr("skip a task");
        case "comment" -> tr("comment on a task");
        case "changeset" -> tr("link a changeset to a task");
        default -> null;
        };
    }

    /**
     * Explain why MapRoulette refused a request from the JOSM login
     *
     * @param reason The reason the server gave
     * @return The explanation
     */
    public static String explainRefusal(MapRouletteUnsupportedException.Reason reason) {
        return switch (reason) {
        case INSUFFICIENT_SCOPE -> tr("This MapRoulette server does not allow this from JOSM.");
        case MOBILE_WRITES_DISABLED -> tr("This MapRoulette server has switched off changes from apps such as JOSM.");
        case CHALLENGE_DRAFT -> tr(
                "This challenge is not published yet (it or its project is disabled), so its tasks can''t be changed.");
        case CHOICE_TASK_STATUS -> tr(
                "This is a multiple-choice task. It is completed by answering it in the Street Tally app, not from JOSM.");
        };
    }

    /**
     * Show a problem as a notification
     *
     * @param text      The plain text, which may hold text from the server
     * @param helpTopic The help topic, or {@code null}
     */
    private static void showError(String text, String helpTopic) {
        notify(Utils.escapeReservedCharactersHTML(text), JOptionPane.WARNING_MESSAGE, helpTopic);
    }

    /**
     * Show a notification. Call on the EDT.
     *
     * @param html      The text, as HTML
     * @param icon      The {@link JOptionPane} message type for the icon
     * @param helpTopic The help topic, or {@code null}
     */
    private static void notify(String html, int icon, String helpTopic) {
        if (GraphicsEnvironment.isHeadless()) {
            Logging.warn(html);
            return;
        }
        final var notification = new Notification(html).setIcon(icon).setDuration(Notification.TIME_LONG);
        if (helpTopic != null) {
            notification.setHelpTopic(helpTopic);
        }
        notification.show();
    }
}
