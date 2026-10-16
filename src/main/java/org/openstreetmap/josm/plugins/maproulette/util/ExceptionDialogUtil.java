// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.util;

import static org.openstreetmap.josm.gui.help.HelpUtil.ht;
import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.Component;
import java.io.IOException;
import java.net.SocketException;
import java.net.UnknownHostException;

import javax.swing.JOptionPane;

import org.openstreetmap.josm.gui.ConditionalOptionPaneUtil;
import org.openstreetmap.josm.gui.HelpAwareOptionPane;
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
import org.openstreetmap.josm.plugins.maproulette.gui.MRGuiHelper;
import org.openstreetmap.josm.tools.ExceptionUtil;

/**
 * This is a more generic class for explaining exceptions, see {@link org.openstreetmap.josm.gui.ExceptionDialogUtil}
 */
public final class ExceptionDialogUtil {
    private ExceptionDialogUtil() {
        // Hide cosntructor
    }

    /**
     * Explain exceptions. This may be called from any thread; the dialog is shown on the EDT.
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
            ConditionalOptionPaneUtil.showMessageDialog("maproulette.user.not.logged.in", MRGuiHelper.dialogParent(),
                    MapRouletteAuth.unavailableMessage(), tr("MapRoulette login unavailable"),
                    JOptionPane.INFORMATION_MESSAGE);
        } else if (exception instanceof MapRouletteLockHeldException lockHeld) {
            if (lockHeld.heldTaskId().isPresent()) {
                showErrorDialog(
                        tr("You already have MapRoulette task {0} started. MapRoulette allows one started task"
                                + " at a time; stop that task first.", lockHeld.heldTaskId().getAsLong()),
                        tr("MapRoulette task already started"), null);
            } else {
                // MapRoulette also answers 409 for conflicts that are not about locks, such as OSM change conflicts
                showErrorDialog(lockHeld.serverMessage().map(m -> tr("MapRoulette reported a conflict: {0}", m))
                        .orElseGet(() -> tr("MapRoulette reported a conflict.")), tr("MapRoulette conflict"), null);
            }
        } else if (exception instanceof MapRouletteUnsupportedException unsupported) {
            showErrorDialog(explainRefusal(unsupported.reason()), tr("Not allowed for this login"), null);
        } else if (exception instanceof MapRouletteForbiddenException) {
            showErrorDialog(tr("MapRoulette did not allow this. Another mapper may be working on the task."),
                    tr("Not allowed"), null);
        } else if (exception instanceof MapRouletteNotFoundException) {
            showErrorDialog(tr("MapRoulette does not have this task or challenge (any more)."), tr("Not found"), null);
        } else if (exception instanceof MapRouletteHttpException httpException && httpException.status() == 400) {
            // MapRoulette's message says why, for example a status change it does not allow or a paused challenge
            showErrorDialog(httpException.serverMessage().map(m -> tr("MapRoulette did not accept this: {0}", m))
                    .orElseGet(() -> tr("MapRoulette did not accept this.")), tr("Not accepted"), null);
        } else if (exception instanceof MapRouletteParseException) {
            showErrorDialog(tr("Could not read the MapRoulette response: {0}", exception.getMessage()),
                    tr("Unexpected MapRoulette response"), null);
        } else if (exception instanceof SocketException socketException) {
            final var message = tr("<html>Failed to open a connection to the remote server<br>" + "''{0}''.<br>"
                    + "Please check your internet connection.", socketException.getMessage()) + "</html>";
            showErrorDialog(message, tr("Network exception"), ht("/ErrorMessages#NestedSocketException"));
        } else if (exception instanceof UnknownHostException unknownHostException) {
            showErrorDialog(unknownHostException.getMessage(), tr("Unknown host"), ht("/ErrorMessages#UnknownHost"));
        } else if (exception instanceof IOException) {
            showErrorDialog(ExceptionUtil.explainException(exception), tr("IO Exception"),
                    ht("/ErrorMessages#NestedIOException"));
        } else {
            org.openstreetmap.josm.gui.ExceptionDialogUtil.explainException(exception);
        }
    }

    /**
     * Explain why MapRoulette refused a request from the JOSM login
     *
     * @param reason The reason the server gave
     * @return The explanation
     */
    public static String explainRefusal(MapRouletteUnsupportedException.Reason reason) {
        return switch (reason) {
        case INSUFFICIENT_SCOPE -> tr(
                "This MapRoulette server or your login does not allow this from JOSM yet. Logging in again may help.");
        case MOBILE_WRITES_DISABLED -> tr("This MapRoulette server has switched off changes from apps such as JOSM.");
        case CHALLENGE_DRAFT -> tr(
                "This challenge is not published yet (it or its project is disabled), so its tasks can''t be changed.");
        case CHOICE_TASK_STATUS -> tr(
                "This is a multiple-choice task. It is completed by answering it in the Street Tally app, not from JOSM.");
        };
    }

    /**
     * Show an error dialog
     * @param msg The message to show
     * @param title The dialog title
     * @param helpTopic The help topic, if present
     * @return See {@link HelpAwareOptionPane#showOptionDialog(Component, Object, String, int, String)}
     */
    private static int showErrorDialog(String msg, String title, String helpTopic) {
        return HelpAwareOptionPane.showOptionDialog(MRGuiHelper.dialogParent(), msg, title, JOptionPane.ERROR_MESSAGE,
                helpTopic);
    }
}
