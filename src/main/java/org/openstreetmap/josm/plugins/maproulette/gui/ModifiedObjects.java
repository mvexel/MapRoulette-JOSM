// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;

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
     * Called on the EDT when the started tasks change
     */
    private static final List<Runnable> LOCK_LISTENERS = new CopyOnWriteArrayList<>();

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
        ids.forEach(id -> {
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
