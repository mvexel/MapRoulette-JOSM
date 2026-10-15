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

import javax.swing.JTable;

import org.openstreetmap.josm.actions.AutoScaleAction;
import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.data.osm.IPrimitive;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.PleaseWaitRunnable;
import org.openstreetmap.josm.plugins.maproulette.api.TaskAPI;
import org.openstreetmap.josm.plugins.maproulette.api.auth.MapRouletteAuth;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteLockHeldException;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedObjects;
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
        final var lockedTasks = new ArrayList<Task>();
        MainApplication.worker.execute(new PleaseWaitRunnable(tr("Starting MapRoulette tasks")) {
            @Override
            protected void cancel() {
                // The requests are short; let them finish
            }

            @Override
            protected void realRun() {
                for (long id : ids) {
                    try {
                        lockedTasks.add(TaskAPI.start(id));
                    } catch (MapRouletteLockHeldException e) {
                        // MapRoulette allows one lock per user, so the remaining rows would fail the same way
                        ExceptionDialogUtil.explainException(e);
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
                }
            }
        });
    }

    /**
     * A task to stop, with the local state read on the EDT
     *
     * @param id       The task id
     * @param locked   The locked task, if any
     * @param modified The pending status change, if any
     */
    private record Stop(long id, Task locked, ModifiedTask modified) {
    }

    /**
     * The outcome of stopping a task, applied on the EDT
     *
     * @param stop      The task that was stopped
     * @param released  {@code true} if the lock and any pending status change are done on the server
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
        final var stops = new ArrayList<Stop>(selected.length);
        for (var index : selected) {
            final var id = data.get(this.table.getRowSorter().convertRowIndexToModel(index)).id();
            final var locked = ModifiedObjects.getLockedTask(id);
            stops.add(new Stop(id, locked, locked == null ? null : ModifiedObjects.getModifiedTask(locked.id())));
        }
        final var results = new ArrayList<Stopped>(stops.size());
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
                        final var unlockedTask = TaskAPI.release(stop.id());
                        if (stop.locked() != null && stop.locked().id() == unlockedTask.id()) {
                            final var modified = stop.modified();
                            if (modified != null) {
                                TaskAPI.updateStatus(stop.locked().id(), modified.status(), modified.comment(),
                                        modified.tags(), modified.reviewRequested(), modified.completionResponses());
                            }
                            released = true;
                        }
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
        if (this.table != null && !this.table.getSelectionModel().isSelectionEmpty()) {
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
     * Show Start if any selected task is not locked, Stop otherwise
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
        new ImageProvider("save").getResource().attachImageIcon(this);
    }
}
