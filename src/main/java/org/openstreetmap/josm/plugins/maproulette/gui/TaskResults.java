// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.GraphicsEnvironment;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.swing.JOptionPane;

import org.openstreetmap.josm.gui.Notification;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.gui.widgets.HtmlPanel;
import org.openstreetmap.josm.plugins.maproulette.api.enums.TaskStatus;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.ListenerList;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Utils;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * What MapRoulette recorded for the tasks the user finished, so the panel can confirm it until the task is started
 * again (#116)
 */
public final class TaskResults {
    /**
     * Whether a task marked Fixed after an upload is linked to its changeset
     */
    public enum Link {
        /** No upload finished the task */
        NONE,
        /** Waits for the changeset to be closed */
        WAITING,
        /** Linked */
        LINKED,
        /** MapRoulette did not link it */
        FAILED
    }

    /**
     * What MapRoulette recorded for a task
     *
     * @param taskId      The task
     * @param status      The status that was sent
     * @param changesetId The changeset of the upload that finished the task, {@code 0} if unknown
     * @param link        Whether the task is linked to the changeset
     */
    public record Result(long taskId, @Nonnull TaskStatus status, long changesetId, @Nonnull Link link) {
        /**
         * Describe the result for the user
         *
         * @return Such as "Task 235 marked Fixed and linked to changeset 702495"
         */
        public String text() {
            return this.text(Long.toString(this.changesetId));
        }

        private String text(String changeset) {
            final var id = Long.toString(this.taskId);
            if (this.status == TaskStatus.SKIPPED) {
                return tr("Task {0} skipped.", id);
            } else if (this.status != TaskStatus.FIXED || this.link == Link.NONE) {
                return tr("Task {0} marked {1}.", id, MRGuiHelper.statusLabel(this.status));
            }
            return switch (this.link) {
            case LINKED -> this.changesetId > 0 ? tr("Task {0} marked Fixed and linked to changeset {1}.", id, changeset)
                    : tr("Task {0} marked Fixed and linked to your changeset.", id);
            case WAITING -> tr("Task {0} marked Fixed. It is linked to changeset {1} once the changeset is closed.", id,
                    changeset);
            default -> tr("Task {0} marked Fixed. The changeset could not be linked.", id);
            };
        }

        /**
         * Describe the result for the user, with the changeset as a link to it on OpenStreetMap
         *
         * @return The text as HTML, without the html element
         */
        public String html() {
            // A marker for the changeset number, which can't occur in the translated text
            final var marker = "\u0000";
            final var text = Utils.escapeReservedCharactersHTML(this.text(marker));
            final var changeset = Long.toString(this.changesetId);
            final var url = Config.getUrls().getBaseBrowseUrl() + "/changeset/" + changeset;
            return text.replace(marker,
                    "<a href=\"" + Utils.escapeReservedCharactersHTML(url) + "\">" + changeset + "</a>");
        }
    }

    /**
     * A task the user finished in this JOSM session, for the done this session list (#117)
     *
     * @param task   The task, as it was when its status was sent
     * @param result What MapRoulette recorded, the latest if the task was finished again
     */
    public record Done(@Nonnull Task task, @Nonnull Result result) {
    }

    /**
     * The results, by task id
     */
    private static final Map<Long, Result> RESULTS = new ConcurrentHashMap<>();
    /**
     * The tasks finished in this JOSM session, oldest first. Starting a task again doesn't remove it.
     */
    private static final Map<Long, Done> SESSION = new LinkedHashMap<>();
    /**
     * Listeners for changed results, called on the EDT
     */
    private static final ListenerList<Runnable> LISTENERS = ListenerList.create();

    private TaskResults() {
        // Hide constructor
    }

    /**
     * Add a listener for changed results. It is called on the EDT.
     *
     * @param listener The listener
     */
    public static void addListener(Runnable listener) {
        LISTENERS.addListener(listener);
    }

    /**
     * Remove a listener
     *
     * @param listener The listener
     */
    public static void removeListener(Runnable listener) {
        LISTENERS.removeListener(listener);
    }

    /**
     * Get what MapRoulette recorded for a task
     *
     * @param taskId The task
     * @return The result, or {@code null} if the task wasn't finished since it was last started
     */
    @Nullable
    public static Result get(long taskId) {
        return RESULTS.get(taskId);
    }

    /**
     * Get the tasks finished in this JOSM session
     *
     * @return The tasks, oldest first
     */
    public static List<Done> session() {
        synchronized (SESSION) {
            return List.copyOf(SESSION.values());
        }
    }

    /**
     * Note that MapRoulette accepted a status
     *
     * @param task   The task
     * @param status The status
     */
    public static void sent(@Nonnull Task task, @Nonnull TaskStatus status) {
        final var result = new Result(task.id(), status, 0, Link.NONE);
        synchronized (SESSION) {
            // Finished again: it moves to the end, with the new result
            SESSION.remove(task.id());
            SESSION.put(task.id(), new Done(task, result));
        }
        put(result, false);
    }

    /**
     * Note whether a task was linked to its changeset, and tell the user
     *
     * @param taskId      The task
     * @param changesetId The changeset, or {@code 0} if MapRoulette found it
     * @param linked      {@code true} if MapRoulette linked it
     */
    public static void linked(long taskId, long changesetId, boolean linked) {
        put(new Result(taskId, TaskStatus.FIXED, changesetId, linked ? Link.LINKED : Link.FAILED), true);
    }

    /**
     * Note that a link waits for its changeset to be closed, and tell the user
     *
     * @param taskId      The task
     * @param changesetId The changeset
     */
    public static void waiting(long taskId, long changesetId) {
        put(new Result(taskId, TaskStatus.FIXED, changesetId, Link.WAITING), true);
    }

    /**
     * Forget a task's result, for example when it is started again
     *
     * @param taskId The task
     */
    public static void clear(long taskId) {
        if (RESULTS.remove(taskId) != null) {
            fire();
        }
    }

    /**
     * Forget all results and the done this session list, for example when the user switches to another MapRoulette
     * server
     */
    public static void clearAll() {
        final boolean hadSession;
        synchronized (SESSION) {
            hadSession = !SESSION.isEmpty();
            SESSION.clear();
        }
        if (!RESULTS.isEmpty() || hadSession) {
            RESULTS.clear();
            fire();
        }
    }

    private static void put(Result result, boolean notify) {
        if (result.link() != Link.NONE) {
            synchronized (SESSION) {
                SESSION.computeIfPresent(result.taskId(), (id, done) -> new Done(done.task(), result));
            }
        }
        // A link can finish after the task was started again, which cleared its result: the new start wins, but the user
        // still hears how the link went
        if (result.link() == Link.NONE || RESULTS.containsKey(result.taskId())
                || ModifiedObjects.getLockedTask(result.taskId()) == null) {
            RESULTS.put(result.taskId(), result);
        }
        // The done this session list shows the link even when the panel has moved on
        fire();
        if (notify) {
            if (GraphicsEnvironment.isHeadless()) {
                Logging.info(result.text());
            } else {
                GuiHelper.runInEDT(() -> notification(result).show());
            }
        }
    }

    /**
     * Get the notification for a result. Call on the EDT.
     *
     * @param result The result
     * @return The notification, whose changeset number links to the changeset
     */
    static Notification notification(Result result) {
        final var content = new HtmlPanel("<html>" + result.html() + "</html>");
        content.enableClickableHyperlinks();
        content.setOpaque(false);
        return new Notification().setContent(content)
                .setIcon(result.link() == Link.FAILED ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE)
                .setDuration(Notification.TIME_LONG);
    }

    private static void fire() {
        GuiHelper.runInEDT(() -> LISTENERS.fireEvent(Runnable::run));
    }
}
