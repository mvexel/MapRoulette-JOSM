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
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.plugins.maproulette.api.auth.MapRouletteAuth;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteAuthUnavailableException;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteException;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteForbiddenException;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteLockHeldException;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteNotFoundException;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteParseException;
import org.openstreetmap.josm.tools.ExceptionUtil;

/**
 * This is a more generic class for explaining exceptions, see {@link org.openstreetmap.josm.gui.ExceptionDialogUtil}
 */
public final class ExceptionDialogUtil {
    private ExceptionDialogUtil() {
        // Hide cosntructor
    }

    /**
     * Explain exceptions
     * @param exception The exception to explain to the user
     */
    public static void explainException(Exception exception) {
        if (exception.getClass() == MapRouletteException.class && exception.getCause()instanceof IOException cause) {
            // A connection problem: explain the underlying exception
            explainException(cause);
        } else if (exception instanceof MapRouletteAuthUnavailableException) {
            final var message = MapRouletteAuth.unavailableMessage();
            GuiHelper.runInEDT(() -> ConditionalOptionPaneUtil.showMessageDialog("maproulette.user.not.logged.in",
                    MainApplication.getMainFrame(), message, tr("MapRoulette login unavailable"),
                    JOptionPane.INFORMATION_MESSAGE));
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
        } else if (exception instanceof MapRouletteForbiddenException) {
            showErrorDialog(tr("MapRoulette did not allow this. Another mapper may be working on the task."),
                    tr("Not allowed"), null);
        } else if (exception instanceof MapRouletteNotFoundException) {
            showErrorDialog(tr("MapRoulette does not have this task or challenge (any more)."), tr("Not found"), null);
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
     * Show an error dialog
     * @param msg The message to show
     * @param title The dialog title
     * @param helpTopic The help topic, if present
     * @return See {@link HelpAwareOptionPane#showOptionDialog(Component, Object, String, int, String)}
     */
    private static int showErrorDialog(String msg, String title, String helpTopic) {
        return HelpAwareOptionPane.showOptionDialog(MainApplication.getMainFrame(), msg, title,
                JOptionPane.ERROR_MESSAGE, helpTopic);
    }
}
