// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.UndoRedoHandler.CommandAddedEvent;
import org.openstreetmap.josm.data.UndoRedoHandler.CommandQueueCleanedEvent;
import org.openstreetmap.josm.data.UndoRedoHandler.CommandQueueListener;
import org.openstreetmap.josm.data.UndoRedoHandler.CommandQueuePreciseListener;
import org.openstreetmap.josm.data.UndoRedoHandler.CommandRedoneEvent;
import org.openstreetmap.josm.data.UndoRedoHandler.CommandUndoneEvent;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.PrimitiveData;
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
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.plugins.maproulette.api_caching.ChallengeCache;
import org.openstreetmap.josm.plugins.maproulette.data.TaskPrimitives;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * The OSM objects the user edits while a task is started (#115). An upload that contains one of them finishes the task.
 * Edits made before the task was started don't count, except unuploaded edits of the task's own objects, for example
 * from an autosave restored after a restart (#141). Undone edits drop out because they are not uploaded.
 * Merging a layer into another drops the edits of the merged layer, since they are recorded by object. Undoing or
 * redoing an edit made before Start doesn't count either, and new objects count only when an edit made after Start
 * adds them, not when they come in with a merged layer (#130).
 */
public final class TaskEdits
        implements DataSetListener, LayerChangeListener, CommandQueueListener, CommandQueuePreciseListener {
    private static final TaskEdits INSTANCE = new TaskEdits();
    /**
     * The touched objects. New objects get their real ids only after the upload, so they are kept by identity.
     */
    private final Set<OsmPrimitive> touched = Collections
            .synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    /**
     * The started tasks' own objects as they were when they first appeared in the data after Start, to show what
     * changed (#148). Edits from before Start are part of it.
     */
    private final Map<OsmPrimitive, PrimitiveData> baseline = Collections.synchronizedMap(new IdentityHashMap<>());
    /**
     * Called on the EDT when the edits change
     */
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean firing = new AtomicBoolean();
    private final Runnable lockListener = this::locksChanged;
    private final Runnable challengeListener = this::adoptTaskObjectEdits;
    /**
     * The edits made while a task is started, so undoing or redoing older edits can be told apart. Kept by identity.
     */
    private final Set<Command> commandsSinceStart = Collections
            .synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    /**
     * What happened since the undo list last changed. JOSM fires the data changes of an undo after the undo event, and
     * those of an edit or redo before it, so they are sorted out once the undo list has changed. Guarded by itself.
     */
    private final Batch batch = new Batch();
    /**
     * The tasks that were started when the lock listener last ran
     */
    private Set<Long> followedTasks = Set.of();
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
            // The task's objects are known once its challenge is cached
            ChallengeCache.addListener(INSTANCE.challengeListener);
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
     * Check if an upload contains an edit made while a task was started that concerns that task: one of its own
     * objects, or an object near it (#149)
     *
     * @param uploaded The primitives of the upload
     * @param task     The task
     * @return {@code true} if it does
     */
    public static boolean touches(Collection<? extends OsmPrimitive> uploaded, Task task) {
        return uploaded.stream().filter(INSTANCE.touched::contains)
                .anyMatch(primitive -> TaskArea.concerns(task, primitive));
    }

    /**
     * Get a task object as it was when it first appeared in the data after Start
     *
     * @param primitive The task object
     * @return Its saved state, or {@code null} if it was not seen while a task was started
     */
    @Nullable
    public static PrimitiveData baseline(@Nonnull OsmPrimitive primitive) {
        return INSTANCE.baseline.get(primitive);
    }

    /**
     * Get the objects created while a task was started that are still waiting for an upload
     *
     * @return The new objects
     */
    @Nonnull
    public static List<OsmPrimitive> created() {
        synchronized (INSTANCE.touched) {
            return INSTANCE.touched.stream().filter(p -> p.isNew() && !p.isDeleted() && p.getDataSet() != null)
                    .toList();
        }
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

    /**
     * Count the commands since Start that are still in the undo list, for tests
     *
     * @return The number of commands
     */
    static int commandsSinceStart() {
        return INSTANCE.commandsSinceStart.size();
    }

    private static boolean pending(OsmPrimitive primitive) {
        return primitive.getDataSet() != null && (primitive.isNew() || primitive.isModified() || primitive.isDeleted());
    }

    /**
     * Follow the OSM data layers while a task is started. A new task starts with no edits, and the edits of a finished
     * or stopped task no longer count.
     */
    private void locksChanged() {
        final var ids = ModifiedObjects.getLockedTasks().stream().map(Task::id).collect(Collectors.toSet());
        final var started = !ids.isEmpty();
        // The listener runs later on the EDT, so the next task may already be started when it learns that one ended
        final var ended = !ids.containsAll(this.followedTasks);
        this.followedTasks = ids;
        if (started && !this.attached) {
            this.attached = true;
            UndoRedoHandler.getInstance().addCommandQueuePreciseListener(this);
            UndoRedoHandler.getInstance().addCommandQueueListener(this);
            MainApplication.getLayerManager().addAndFireLayerChangeListener(this);
        } else if (!started && this.attached) {
            this.attached = false;
            UndoRedoHandler.getInstance().removeCommandQueuePreciseListener(this);
            UndoRedoHandler.getInstance().removeCommandQueueListener(this);
            MainApplication.getLayerManager().removeAndFireLayerChangeListener(this);
        }
        if (!started || ended) {
            this.commandsSinceStart.clear();
            synchronized (this.batch) {
                this.batch.clear();
            }
            if (!this.touched.isEmpty()) {
                clear();
            }
            // The next task takes its own baseline below, with the last task's edits in it
            this.baseline.clear();
        }
        adoptTaskObjectEdits();
    }

    /**
     * Count the unuploaded edits of the started tasks' own objects, even if they were made before Start. After a
     * restart, the edits come back with the autosave, but the task is started again only afterwards (#141).
     */
    private void adoptTaskObjectEdits() {
        if (!this.attached) {
            return;
        }
        final var edited = new ArrayList<OsmPrimitive>();
        for (var task : ModifiedObjects.getLockedTasks()) {
            final var ids = TaskPrimitives.cachedPrimitiveIds(task).orElse(List.of());
            for (var layer : MainApplication.getLayerManager().getLayersOfType(OsmDataLayer.class)) {
                for (var id : ids) {
                    final var primitive = layer.getDataSet().getPrimitiveById(id);
                    if (primitive != null && !primitive.isIncomplete()) {
                        // Taken when the object first shows up for a started task. If the challenge is cached only
                        // after Start, edits made in between count as made before Start.
                        this.baseline.computeIfAbsent(primitive, OsmPrimitive::save);
                    }
                    if (primitive != null && pending(primitive)) {
                        edited.add(primitive);
                    }
                }
            }
        }
        touch(edited);
    }

    private void touch(Collection<? extends OsmPrimitive> primitives) {
        if (ModifiedObjects.getLockedTasks().isEmpty() || primitives.isEmpty()) {
            return;
        }
        var added = false;
        synchronized (this.batch) {
            for (var primitive : primitives) {
                if (this.touched.add(primitive)) {
                    this.batch.touched.add(primitive);
                    added = true;
                }
            }
        }
        // Another edit of an object already counted still changes what the task's changes show (#148)
        if (added || !this.baseline.isEmpty()) {
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
        // Downloads add objects too; only new objects are edits, and only once an edit made after Start turns out to
        // have added them. A download can bring the task's own objects.
        if (!ModifiedObjects.getLockedTasks().isEmpty()) {
            synchronized (this.batch) {
                event.getPrimitives().stream().filter(OsmPrimitive::isNew).forEach(this.batch.added::add);
            }
        }
        if (event.getPrimitives().stream().anyMatch(p -> !p.isNew())) {
            adoptTaskObjectEdits();
        }
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
        // JOSM folds the events of a command that touches many objects into one DataChangedEvent
        final var events = event.getEvents();
        if (events != null) {
            events.forEach(e -> e.fire(this));
        } else if (event.getDataset() != null) {
            // No details: count the objects waiting for an upload
            touch(event.getDataset().allPrimitives().stream().filter(TaskEdits::pending).toList());
        }
        fire();
    }

    @Override
    public void commandAdded(CommandAddedEvent e) {
        this.commandsSinceStart.add(e.getCommand());
        noteCommand(e.getCommand());
    }

    @Override
    public void commandUndone(CommandUndoneEvent e) {
        noteCommand(e.getCommand());
    }

    @Override
    public void commandRedone(CommandRedoneEvent e) {
        noteCommand(e.getCommand());
    }

    @Override
    public void cleaned(CommandQueueCleanedEvent e) {
        // The undo list lost entries; edits made since Start stay edits
    }

    private void noteCommand(Command command) {
        final var participants = command.getParticipatingPrimitives();
        synchronized (this.batch) {
            (this.commandsSinceStart.contains(command) ? this.batch.sinceStart : this.batch.beforeStart)
                    .addAll(participants);
        }
    }

    @Override
    public void commandChanged(int queueSize, int redoSize) {
        if (this.commandsSinceStart.size() > queueSize + redoSize) {
            // JOSM dropped the oldest commands from its undo list, or cleaned it
            final var kept = Collections.newSetFromMap(new IdentityHashMap<Command, Boolean>());
            kept.addAll(UndoRedoHandler.getInstance().getUndoCommands());
            kept.addAll(UndoRedoHandler.getInstance().getRedoCommands());
            this.commandsSinceStart.retainAll(kept);
        }
        final List<OsmPrimitive> created;
        final var dropped = new ArrayList<OsmPrimitive>();
        synchronized (this.batch) {
            // New objects that an edit made after Start added, or brought back
            created = this.batch.added.stream().filter(this.batch.sinceStart::contains).toList();
            // Objects touched only by undoing or redoing an edit made before Start
            for (var primitive : this.batch.touched) {
                if (this.batch.beforeStart.contains(primitive) && !this.batch.sinceStart.contains(primitive)) {
                    dropped.add(primitive);
                }
            }
            this.batch.clear();
        }
        if (!dropped.isEmpty()) {
            dropped.forEach(this.touched::remove);
            fire();
        }
        touch(created);
        // An undone edit of the task's own objects still counts if it is not uploaded yet (#141)
        if (!dropped.isEmpty()) {
            adoptTaskObjectEdits();
        }
    }

    /**
     * What happened since the undo list last changed
     */
    private static final class Batch {
        /**
         * Objects that started to count as edits
         */
        final Set<OsmPrimitive> touched = Collections.newSetFromMap(new IdentityHashMap<>());
        /**
         * New objects that appeared in the data
         */
        final Set<OsmPrimitive> added = Collections.newSetFromMap(new IdentityHashMap<>());
        /**
         * Objects of edits made after Start that were made, undone or redone
         */
        final Set<OsmPrimitive> sinceStart = Collections.newSetFromMap(new IdentityHashMap<>());
        /**
         * Objects of edits made before Start that were undone or redone
         */
        final Set<OsmPrimitive> beforeStart = Collections.newSetFromMap(new IdentityHashMap<>());

        void clear() {
            this.touched.clear();
            this.added.clear();
            this.sinceStart.clear();
            this.beforeStart.clear();
        }
    }

    @Override
    public void layerAdded(LayerAddEvent e) {
        if (e.getAddedLayer()instanceof OsmDataLayer layer) {
            layer.getDataSet().addDataSetListener(this);
            adoptTaskObjectEdits();
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
            synchronized (this.baseline) {
                this.baseline.keySet().removeIf(p -> p.getDataSet() == dataSet || p.getDataSet() == null);
            }
            fire();
        }
    }

    @Override
    public void layerOrderChanged(LayerOrderChangeEvent e) {
        // Not needed
    }
}
