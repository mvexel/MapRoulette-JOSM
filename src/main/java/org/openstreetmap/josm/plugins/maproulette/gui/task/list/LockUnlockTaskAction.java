// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui.task.list;

import static org.openstreetmap.josm.gui.help.HelpUtil.ht;
import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.io.Serial;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.swing.JOptionPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;

import org.openstreetmap.josm.actions.AutoScaleAction;
import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.data.osm.IPrimitive;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.PleaseWaitRunnable;
import org.openstreetmap.josm.plugins.maproulette.api.TaskAPI;
import org.openstreetmap.josm.plugins.maproulette.api.auth.MapRouletteAuth;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteLockHeldException;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.plugins.maproulette.gui.MRGuiHelper;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedObjects;
import org.openstreetmap.josm.plugins.maproulette.gui.TaskData;
import org.openstreetmap.josm.plugins.maproulette.gui.task.current.CurrentTaskPanel;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedTask;
import org.openstreetmap.josm.plugins.maproulette.gui.layer.MapRouletteClusteredPointLayer;
import org.openstreetmap.josm.plugins.maproulette.util.ExceptionDialogUtil;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * Lock tasks
 */
final class LockUnlockTaskAction extends JosmAction {
    /**
     * The serial UID for this component
     */
    @Serial
    private static final long serialVersionUID = 3686177310606460566L;
    /**
     * The table to update
     */
    private final JTable table;
    /**
     * {@code true} while a start or stop runs, so that a double-click doesn't send it twice (EDT only)
     */
    private boolean running;

    /**
     * Create a new action
     *
     * @param table The table to update
     */
    LockUnlockTaskAction(JTable table) {
        super(tr("Start Task"), "lock", tr("Start MapRoulette Tasks"),
                Shortcut.registerShortcut("maproulette:start_stop_tasks", tr("MapRoulette: Start or Stop Tasks"),
                        KeyEvent.CHAR_UNDEFINED, Shortcut.NONE),
                false);
        Objects.requireNonNull(table, "table");
        putValue("help", ht("/Dialog/MapRouletteStartTask"));
        this.table = table;
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        if (this.running) {
            return;
        }
        if (Boolean.TRUE.equals(getValue("lock"))) {
            lockTasks();
        } else {
            unlockTasks();
        }
    }

    /**
     * Perform lock actions. The requests run on the worker; the table and {@link ModifiedObjects} are only touched on
     * the EDT.
     */
    private void lockTasks() {
        final var selected = this.table.getSelectionModel().getSelectedIndices();
        final var model = (TaskTableModel) this.table.getModel();
        final var ids = new long[selected.length];
        for (int j = 0; j < selected.length; j++) {
            ids[j] = model.get(this.table.getRowSorter().convertRowIndexToModel(selected[j])).id();
        }
        lockTasks(ids, selected, -1);
    }

    /**
     * Start tasks
     *
     * @param ids          The tasks to start
     * @param selected     The table selection to restore
     * @param releaseFirst A task to release before starting, or -1
     */
    private void lockTasks(long[] ids, int[] selected, long releaseFirst) {
        final var model = (TaskTableModel) this.table.getModel();
        final var lockedTasks = new ArrayList<Task>();
        // A lock MapRoulette still has for the user, but this JOSM does not know (from an earlier session), and the
        // tasks that could not be started because of it
        final var forgotten = new long[] { -1 };
        final var notStarted = new ArrayList<Long>();
        setRunning(true);
        MainApplication.worker.execute(new PleaseWaitRunnable(tr("Starting MapRoulette tasks")) {
            @Override
            protected void cancel() {
                // The requests are short; let them finish
            }

            @Override
            protected void realRun() {
                if (releaseFirst >= 0) {
                    try {
                        TaskAPI.release(releaseFirst);
                    } catch (IOException e) {
                        ExceptionDialogUtil.explainException(e);
                        return;
                    }
                }
                for (var i = 0; i < ids.length; i++) {
                    try {
                        lockedTasks.add(TaskAPI.start(ids[i]));
                    } catch (MapRouletteLockHeldException e) {
                        final var held = e.heldTaskId();
                        if (held.isPresent() && held.getAsLong() == ids[i]) {
                            // Already started by the user, for example by an earlier click
                            if (!addStarted(lockedTasks, ids[i])) {
                                break;
                            }
                            continue;
                        }
                        if (releaseFirst < 0 && held.isPresent()
                                && ModifiedObjects.getLockedTask(held.getAsLong()) == null) {
                            // JOSM can't stop it from the task list, so ask in finish()
                            forgotten[0] = held.getAsLong();
                            for (var j = i; j < ids.length; j++) {
                                notStarted.add(ids[j]);
                            }
                        } else {
                            ExceptionDialogUtil.explainException(e);
                        }
                        // MapRoulette allows one lock per user, so the remaining rows would fail the same way
                        break;
                    } catch (IOException e) {
                        ExceptionDialogUtil.explainException(e);
                    }
                }
            }

            @Override
            protected void finish() {
                lockedTasks.forEach(ModifiedObjects::addLockedTask);
                model.fireTableDataChanged();
                reselect(selected);
                if (!lockedTasks.isEmpty()) {
                    final List<IPrimitive> primitiveList = new ArrayList<>();
                    for (var task : lockedTasks) {
                        if (task.geometries().allPrimitives().isEmpty()) {
                            primitiveList.add(task.location());
                        } else {
                            primitiveList.addAll(task.geometries().allPrimitives());
                        }
                    }
                    AutoScaleAction.zoomTo(primitiveList);
                    // Show the started task, so the user finds its instructions and status buttons (#92)
                    CurrentTaskPanel.show(lockedTasks.get(0), true);
                    // Load the task's data and select its objects, so the user can edit right away (#114)
                    TaskData.loadAndSelect(lockedTasks.get(0));
                }
                if (forgotten[0] >= 0) {
                    // Ask after this job, so the question is not tied to its progress dialog
                    final long held = forgotten[0];
                    final var remaining = notStarted.stream().mapToLong(Long::longValue).toArray();
                    SwingUtilities.invokeLater(() -> {
                        if (confirmStopForgotten(held)) {
                            lockTasks(remaining, selected, held);
                        }
                    });
                }
            }

            @Override
            protected void afterFinish() {
                setRunning(false);
            }
        });
    }

    /**
     * Add a task the user already started
     *
     * @param lockedTasks The started tasks
     * @param id          The task id
     * @return {@code true} if it could be read
     */
    private static boolean addStarted(List<Task> lockedTasks, long id) {
        try {
            lockedTasks.add(TaskAPI.get(id));
            return true;
        } catch (IOException e) {
            ExceptionDialogUtil.explainException(e);
            return false;
        }
    }

    /**
     * Mark a start or stop as running, and disable the action meanwhile
     *
     * @param running {@code true} while it runs
     */
    private void setRunning(boolean running) {
        this.running = running;
        updateEnabledState();
    }

    /**
     * Ask whether to stop a task the user started in an earlier session, which JOSM no longer knows about
     *
     * @param held The task the user has started
     * @return {@code true} to stop it and start the selected tasks
     */
    private static boolean confirmStopForgotten(long held) {
        final var options = new String[] { tr("Stop it and start this task"), tr("Cancel") };
        final var choice = JOptionPane.showOptionDialog(MRGuiHelper.dialogParent(),
                tr("You still have MapRoulette task {0} started, probably from an earlier JOSM session.\n"
                        + "MapRoulette allows one started task at a time. Stop task {0} and start this task?",
                        Long.toString(held)),
                tr("MapRoulette task already started"), JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                options, options[0]);
        return choice == 0;
    }

    /**
     * A task to stop, with the local state read on the EDT
     *
     * @param id       The task id
     * @param locked   The locked task, if any
     * @param modified The unsent answers, if any
     */
    private record Stop(long id, Task locked, ModifiedTask modified) {
    }

    /**
     * The outcome of stopping a task, applied on the EDT
     *
     * @param stop      The task that was stopped
     * @param released  {@code true} if the lock is released on the server
     * @param refreshed The refreshed task, or {@code null}
     */
    private record Stopped(Stop stop, boolean released, Task refreshed) {
    }

    /**
     * Perform unlock actions. The requests run on the worker; the table and {@link ModifiedObjects} are only touched on
     * the EDT.
     */
    private void unlockTasks() {
        final var selected = this.table.getSelectedRows();
        final var data = (TaskTableModel) this.table.getModel();
        final var ids = new ArrayList<Long>(selected.length);
        for (var index : selected) {
            ids.add(data.get(this.table.getRowSorter().convertRowIndexToModel(index)).id());
        }
        if (ids.isEmpty()) {
            // With no selection, Stop stops the started tasks (#108)
            ModifiedObjects.getLockedTasks().forEach(task -> ids.add(task.id()));
        }
        if (ids.isEmpty()) {
            return;
        }
        final var stops = new ArrayList<Stop>(ids.size());
        for (var id : ids) {
            final var locked = ModifiedObjects.getLockedTask(id);
            stops.add(new Stop(id, locked, locked == null ? null : ModifiedObjects.getModifiedTask(locked.id())));
        }
        final var results = new ArrayList<Stopped>(stops.size());
        setRunning(true);
        MainApplication.worker.execute(new PleaseWaitRunnable(tr("Stopping MapRoulette tasks")) {
            @Override
            protected void cancel() {
                // The requests are short; let them finish
            }

            @Override
            protected void realRun() {
                for (var stop : stops) {
                    var released = false;
                    try {
                        // A picked status is sent at once (D15), so Stop only releases the task
                        final var unlockedTask = TaskAPI.release(stop.id());
                        released = stop.locked() != null && stop.locked().id() == unlockedTask.id();
                        results.add(new Stopped(stop, released, TaskAPI.get(stop.id())));
                    } catch (IOException ioException) {
                        if (released) {
                            results.add(new Stopped(stop, true, null));
                        }
                        ExceptionDialogUtil.explainException(ioException);
                    }
                }
            }

            @Override
            protected void finish() {
                for (var result : results) {
                    if (result.released()) {
                        if (result.stop().modified() != null) {
                            ModifiedObjects.removeModifiedTask(result.stop().modified());
                        }
                        ModifiedObjects.removeLockedTask(result.stop().locked());
                    }
                    final var newPoint = result.refreshed();
                    if (newPoint != null) {
                        MainApplication.getLayerManager().getLayersOfType(MapRouletteClusteredPointLayer.class)
                                .forEach(layer -> layer.refreshTasks(Map.of(newPoint.id(), newPoint)));
                    }
                }
                data.fireTableDataChanged();
                reselect(selected);
            }

            @Override
            protected void afterFinish() {
                setRunning(false);
            }
        });
    }

    /**
     * Reselect a selection after indicating that all data in the table changed
     *
     * @param selected The selection to reselect
     */
    private void reselect(int[] selected) {
        final var selectionModel = this.table.getSelectionModel();
        selectionModel.clearSelection();
        selectionModel.setValueIsAdjusting(true);
        try {
            for (var index : selected) {
                selectionModel.setSelectionInterval(index, index);
            }
        } finally {
            selectionModel.setValueIsAdjusting(false);
        }
    }

    @Override
    public void updateEnabledState() {
        if (this.running) {
            super.setEnabled(false);
        } else if (this.table != null && (!this.table.getSelectionModel().isSelectionEmpty()
                || !ModifiedObjects.getLockedTasks().isEmpty())) {
            updateStartOrStop();
            if (MapRouletteAuth.isAvailable()) {
                super.setEnabled(true);
            } else {
                // Start and Stop need a MapRoulette login; keep the button visible and explain why it is disabled
                this.setTooltip(MapRouletteAuth.unavailableTooltip());
                super.setEnabled(false);
            }
        } else {
            super.setEnabled(false);
        }
    }

    /**
     * Show Start if any selected task is not locked, Stop otherwise. With no selection, Stop stops the started tasks.
     */
    private void updateStartOrStop() {
        for (var i : this.table.getSelectedRows()) {
            final var index = this.table.getRowSorter().convertRowIndexToModel(i);
            final var point = ((TaskTableModel) this.table.getModel()).get(index);
            if (ModifiedObjects.getLockedTask(point.id()) == null) {
                this.putValue(NAME, tr("Start Task"));
                this.setTooltip(tr("Start MapRoulette Tasks"));
                new ImageProvider("lock").getResource().attachImageIcon(this);
                putValue("lock", true);
                return;
            }
        }
        putValue("lock", false);
        this.putValue(NAME, tr("Stop Task"));
        this.setTooltip(tr("Stop MapRoulette Tasks"));
        new ImageProvider("presets/vehicle/restriction/stop").getResource().attachImageIcon(this);
    }
}
