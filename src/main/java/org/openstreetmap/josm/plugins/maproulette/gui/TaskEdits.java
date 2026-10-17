// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui;

import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.event.AbstractDatasetChangedEvent;
import org.openstreetmap.josm.data.osm.event.DataChangedEvent;
import org.openstreetmap.josm.data.osm.event.DataSetListener;
import org.openstreetmap.josm.data.osm.event.NodeMovedEvent;
import org.openstreetmap.josm.data.osm.event.PrimitivesAddedEvent;
import org.openstreetmap.josm.data.osm.event.PrimitivesRemovedEvent;
import org.openstreetmap.josm.data.osm.event.RelationMembersChangedEvent;
import org.openstreetmap.josm.data.osm.event.TagsChangedEvent;
import org.openstreetmap.josm.data.osm.event.WayNodesChangedEvent;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.LayerManager.LayerAddEvent;
import org.openstreetmap.josm.gui.layer.LayerManager.LayerChangeListener;
import org.openstreetmap.josm.gui.layer.LayerManager.LayerOrderChangeEvent;
import org.openstreetmap.josm.gui.layer.LayerManager.LayerRemoveEvent;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.gui.util.GuiHelper;

import jakarta.annotation.Nonnull;

/**
 * The OSM objects the user edits while a task is started (#115). An upload that contains one of them finishes the task.
 * Edits made before the task was started don't count, and undone edits drop out because they are not uploaded.
 */
public final class TaskEdits implements DataSetListener, LayerChangeListener {
    private static final TaskEdits INSTANCE = new TaskEdits();
    /**
     * The touched objects. New objects get their real ids only after the upload, so they are kept by identity.
     */
    private final Set<OsmPrimitive> touched = Collections
            .synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    /**
     * Called on the EDT when the edits change
     */
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean firing = new AtomicBoolean();
    private final Runnable lockListener = this::locksChanged;
    private boolean started;
    /**
     * {@code true} while the OSM data layers are followed, which is while a task is started
     */
    private boolean attached;

    private TaskEdits() {
        // Use the instance
    }

    /**
     * Start following the started tasks, and the edits in the OSM data layers while a task is started. Call it when the
     * plugin starts.
     */
    public static void start() {
        if (!INSTANCE.started) {
            INSTANCE.started = true;
            ModifiedObjects.addLockListener(INSTANCE.lockListener);
        }
        GuiHelper.runInEDT(INSTANCE::locksChanged);
    }

    /**
     * Listen for changes to the edits, for example to show their count
     *
     * @param listener Called on the EDT after the edits change
     */
    public static void addListener(@Nonnull Runnable listener) {
        INSTANCE.listeners.add(listener);
    }

    /**
     * Stop listening for changes to the edits
     *
     * @param listener The listener to remove
     */
    public static void removeListener(@Nonnull Runnable listener) {
        INSTANCE.listeners.remove(listener);
    }

    /**
     * Count the edits made while a task was started that are still waiting for an upload
     *
     * @return The number of edited objects
     */
    public static int count() {
        synchronized (INSTANCE.touched) {
            return (int) INSTANCE.touched.stream().filter(TaskEdits::pending).count();
        }
    }

    /**
     * Check if an upload contains an edit made while a task was started
     *
     * @param uploaded The primitives of the upload
     * @return {@code true} if it does
     */
    public static boolean touches(Collection<? extends OsmPrimitive> uploaded) {
        return uploaded.stream().anyMatch(INSTANCE.touched::contains);
    }

    /**
     * Forget the edits, for example once the task is finished
     */
    public static void clear() {
        INSTANCE.touched.clear();
        INSTANCE.fire();
    }

    /**
     * Record that an object was edited, for tests
     *
     * @param primitive The edited object
     */
    static void record(OsmPrimitive primitive) {
        INSTANCE.touch(List.of(primitive));
    }

    private static boolean pending(OsmPrimitive primitive) {
        return primitive.getDataSet() != null && (primitive.isNew() || primitive.isModified() || primitive.isDeleted());
    }

    /**
     * Follow the OSM data layers while a task is started. A new task starts with no edits, and the edits of a finished
     * or stopped task no longer count.
     */
    private void locksChanged() {
        final var started = !ModifiedObjects.getLockedTasks().isEmpty();
        if (started && !this.attached) {
            this.attached = true;
            MainApplication.getLayerManager().addAndFireLayerChangeListener(this);
        } else if (!started && this.attached) {
            this.attached = false;
            MainApplication.getLayerManager().removeAndFireLayerChangeListener(this);
        }
        if (!started && !this.touched.isEmpty()) {
            clear();
        }
    }

    private void touch(Collection<? extends OsmPrimitive> primitives) {
        if (ModifiedObjects.getLockedTasks().isEmpty() || primitives.isEmpty()) {
            return;
        }
        if (this.touched.addAll(primitives)) {
            fire();
        }
    }

    private void fire() {
        // Coalesce: one data change can touch thousands of objects
        if (!this.listeners.isEmpty() && this.firing.compareAndSet(false, true)) {
            GuiHelper.runInEDT(() -> {
                this.firing.set(false);
                this.listeners.forEach(Runnable::run);
            });
        }
    }

    @Override
    public void primitivesAdded(PrimitivesAddedEvent event) {
        // Downloads add objects too; only new objects are edits
        touch(event.getPrimitives().stream().filter(OsmPrimitive::isNew).toList());
    }

    @Override
    public void primitivesRemoved(PrimitivesRemovedEvent event) {
        // Removed objects are not uploaded (a deleted object stays in the data set, marked deleted)
        fire();
    }

    @Override
    public void tagsChanged(TagsChangedEvent event) {
        touch(event.getPrimitives());
    }

    @Override
    public void nodeMoved(NodeMovedEvent event) {
        touch(event.getPrimitives());
    }

    @Override
    public void wayNodesChanged(WayNodesChangedEvent event) {
        touch(event.getPrimitives());
    }

    @Override
    public void relationMembersChanged(RelationMembersChangedEvent event) {
        touch(event.getPrimitives());
    }

    @Override
    public void otherDatasetChange(AbstractDatasetChangedEvent event) {
        // Deletions are marked here (a deleted object stays in the data set until it is uploaded)
        touch(event.getPrimitives().stream().filter(OsmPrimitive::isDeleted).toList());
    }

    @Override
    public void dataChanged(DataChangedEvent event) {
        // A bulk change, such as a download or a merge: not an edit by itself
        fire();
    }

    @Override
    public void layerAdded(LayerAddEvent e) {
        if (e.getAddedLayer()instanceof OsmDataLayer layer) {
            layer.getDataSet().addDataSetListener(this);
        }
    }

    @Override
    public void layerRemoving(LayerRemoveEvent e) {
        if (e.getRemovedLayer()instanceof OsmDataLayer layer) {
            final DataSet dataSet = layer.getDataSet();
            dataSet.removeDataSetListener(this);
            synchronized (this.touched) {
                this.touched.removeIf(p -> p.getDataSet() == dataSet || p.getDataSet() == null);
            }
            fire();
        }
    }

    @Override
    public void layerOrderChanged(LayerOrderChangeEvent e) {
        // Not needed
    }
}
