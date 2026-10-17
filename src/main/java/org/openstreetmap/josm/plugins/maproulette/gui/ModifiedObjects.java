// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteClient;
import org.openstreetmap.josm.plugins.maproulette.api.model.ClusteredPoint;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.plugins.maproulette.api.model.TaskClusteredPoint;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * A class holding modified objects. It is used from both the EDT and the worker, so it is thread-safe.
 */
public final class ModifiedObjects {
    /**
     * The current task list (these should all be locked tasks)
     */
    private static final Map<Long, Task> LOCKED_TASK_MAP = new ConcurrentSkipListMap<>();
    /**
     * The modified task list
     */
    private static final Map<Long, ModifiedTask> MODIFIED_TASK_MAP = new ConcurrentSkipListMap<>();
    /**
     * The tasks marked Fixed whose edits are not uploaded yet
     */
    private static final Map<Long, Task> UNLINKED_FIXED_MAP = new ConcurrentSkipListMap<>();
    /**
     * The tasks the upload in progress finishes, with what to send once it succeeds (#115)
     */
    private static final Map<Long, ModifiedTask> FINISHING_MAP = new ConcurrentSkipListMap<>();
    /**
     * Called on the EDT when the started tasks change
     */
    private static final List<Runnable> LOCK_LISTENERS = new CopyOnWriteArrayList<>();
    /**
     * The tasks an upload in progress finishes, from the upload until Fixed is sent or the upload fails
     */
    private static final Set<Long> FINISH_PENDING = ConcurrentHashMap.newKeySet();

    /**
     * Don't allow this static class to be instantiated
     */
    private ModifiedObjects() {
        // Hide constructor
    }

    /**
     * Add a modified task
     *
     * @param task The modified task
     * @return {@code true} if the task list changed
     */
    public static boolean addLockedTask(Task task) {
        // A new lock: refresh it even if the old one was lost
        LockRefresher.forget(task.id());
        final var changed = LOCKED_TASK_MAP.put(task.id(), task) == null;
        if (changed) {
            fireLocksChanged();
        }
        return changed;
    }

    /**
     * Remove a modified task
     *
     * @param task The modified task
     * @return {@code true} if the task list changed
     */
    public static boolean removeLockedTask(Task task) {
        final var changed = LOCKED_TASK_MAP.remove(task.id()) != null;
        if (changed) {
            fireLocksChanged();
        }
        return changed;
    }

    /**
     * Listen for changes to the started tasks, for example to update Stop when a lock is dropped elsewhere
     *
     * @param listener Called on the EDT after a task is started or stopped
     */
    public static void addLockListener(@Nonnull Runnable listener) {
        LOCK_LISTENERS.add(listener);
    }

    /**
     * Stop listening for changes to the started tasks
     *
     * @param listener The listener to remove
     */
    public static void removeLockListener(@Nonnull Runnable listener) {
        LOCK_LISTENERS.remove(listener);
    }

    private static void fireLocksChanged() {
        if (!LOCK_LISTENERS.isEmpty()) {
            GuiHelper.runInEDT(() -> LOCK_LISTENERS.forEach(Runnable::run));
        }
    }

    /**
     * Get the current task list
     *
     * @return The task list
     */
    @Nonnull
    public static List<Task> getLockedTasks() {
        return List.copyOf(LOCKED_TASK_MAP.values());
    }

    /**
     * Get a specific task
     *
     * @param id The task to get
     * @return the task, or {@code null}
     */
    @Nullable
    public static Task getLockedTask(long id) {
        return LOCKED_TASK_MAP.get(id);
    }

    /**
     * Check if another mapper holds a lock on a task. Without a MapRoulette user id, the tasks this plugin started count
     * as the user's.
     *
     * @param point The task to check
     * @return {@code true} if MapRoulette reported a lock on the task by someone other than the logged-in user, and
     *         this plugin did not start it. Without a known user id, every lock counts as another mapper's.
     */
    public static boolean isLockedByOther(@Nonnull TaskClusteredPoint point) {
        if (!(point instanceof ClusteredPoint clusteredPoint) || clusteredPoint.lockedBy() == null
                || getLockedTask(point.id()) != null) {
            return false;
        }
        // A lock the user took in an earlier session or on another device is theirs
        final var self = MapRouletteClient.getInstance().auth().knownUserId();
        return self.isEmpty() || self.getAsLong() != clusteredPoint.lockedBy();
    }

    /**
     * Get the modified tasks
     *
     * @return An unmodifiable list of modified tasks
     */
    @Nonnull
    public static List<ModifiedTask> getModifiedTasks() {
        return List.copyOf(MODIFIED_TASK_MAP.values());
    }

    /**
     * Add a modified task
     *
     * @param task The task to add
     * @return {@code true} if the task was added
     */
    public static boolean addModifiedTask(ModifiedTask task) {
        return MODIFIED_TASK_MAP.put(task.task().id(), task) == null;
    }

    /**
     * Remove a modified task, unless it was replaced by a newer change in the meantime
     *
     * @param task The task to remove
     * @return {@code true} if the task was removed
     */
    public static boolean removeModifiedTask(ModifiedTask task) {
        return MODIFIED_TASK_MAP.remove(task.task().id(), task);
    }

    /**
     * Get the task modification for a specified task id
     *
     * @param id The id to get the task modification for
     * @return The modified task, or {@code null}
     */
    @Nullable
    public static ModifiedTask getModifiedTask(long id) {
        return MODIFIED_TASK_MAP.get(id);
    }

    /**
     * Remember a task marked Fixed, so that it is linked to the changeset of the next upload
     *
     * @param task The task
     */
    public static void addUnlinkedFixed(Task task) {
        UNLINKED_FIXED_MAP.put(task.id(), task);
    }

    /**
     * Forget a task marked Fixed, once it is linked or its status was not sent
     *
     * @param task The task
     */
    public static void removeUnlinkedFixed(Task task) {
        UNLINKED_FIXED_MAP.remove(task.id());
    }

    /**
     * Get the tasks marked Fixed whose edits are not uploaded yet
     *
     * @return The tasks
     */
    @Nonnull
    public static List<Task> getUnlinkedFixed() {
        return List.copyOf(UNLINKED_FIXED_MAP.values());
    }

    /**
     * Record the tasks the upload in progress finishes. Their status is sent once the upload succeeds.
     *
     * @param tasks The statuses to send, replacing those of an earlier upload that did not happen
     */
    public static void setFinishing(Collection<ModifiedTask> tasks) {
        FINISHING_MAP.clear();
        tasks.forEach(task -> FINISHING_MAP.put(task.task().id(), task));
    }

    /**
     * Get the tasks the upload in progress finishes
     *
     * @return The statuses to send once the upload succeeds
     */
    @Nonnull
    public static List<ModifiedTask> getFinishing() {
        return List.copyOf(FINISHING_MAP.values());
    }

    /**
     * Get and forget the tasks the upload in progress finishes
     *
     * @return The statuses to send once the upload succeeds
     */
    @Nonnull
    public static synchronized List<ModifiedTask> takeFinishing() {
        final var finishing = getFinishing();
        finishing.forEach(task -> FINISHING_MAP.remove(task.task().id(), task));
        return finishing;
    }

    /**
     * Mark tasks as finished by the upload in progress, or no longer. Their status buttons and Stop are disabled
     * meanwhile, so the user's choice can't be overwritten by the Fixed sent after the upload.
     *
     * @param ids     The tasks
     * @param pending {@code true} while the upload runs and Fixed is sent
     */
    public static void setFinishPending(Collection<Long> ids, boolean pending) {
        if (pending ? FINISH_PENDING.addAll(ids) : FINISH_PENDING.removeAll(ids)) {
            fireLocksChanged();
        }
    }

    /**
     * Check if the upload in progress finishes a task
     *
     * @param id The task
     * @return {@code true} until Fixed is sent or the upload fails
     */
    public static boolean isFinishPending(long id) {
        return FINISH_PENDING.contains(id);
    }

    /**
     * Forget every task, for example when the user switches to another MapRoulette server. Nothing is sent.
     *
     * @return The ids of the tasks that were forgotten
     */
    @Nonnull
    public static Set<Long> forgetAll() {
        final var ids = new TreeSet<Long>();
        final var hadLocks = !LOCKED_TASK_MAP.isEmpty();
        ids.addAll(LOCKED_TASK_MAP.keySet());
        ids.addAll(MODIFIED_TASK_MAP.keySet());
        ids.addAll(UNLINKED_FIXED_MAP.keySet());
        ids.addAll(FINISHING_MAP.keySet());
        ids.addAll(FINISH_PENDING);
        FINISH_PENDING.clear();
        ids.forEach(id -> {
            FINISHING_MAP.remove(id);
            LOCKED_TASK_MAP.remove(id);
            MODIFIED_TASK_MAP.remove(id);
            UNLINKED_FIXED_MAP.remove(id);
        });
        if (hadLocks) {
            fireLocksChanged();
        }
        return ids;
    }
}
