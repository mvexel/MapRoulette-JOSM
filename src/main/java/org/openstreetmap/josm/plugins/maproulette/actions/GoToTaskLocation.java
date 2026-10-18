// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.actions;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.io.Serial;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;

import org.openstreetmap.josm.actions.AutoScaleAction;
import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.osm.BBox;
import org.openstreetmap.josm.data.osm.IPrimitive;
import org.openstreetmap.josm.data.osm.PrimitiveId;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.plugins.maproulette.api.model.ClusteredPoint;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.plugins.maproulette.api_caching.TaskCache;
import org.openstreetmap.josm.plugins.maproulette.data.TaskPrimitives;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * Go to a task location, and select the task's objects that are loaded, as the current task panel's Zoom to task does
 * (#188)
 */
public class GoToTaskLocation extends JosmAction {
    /**
     * The serial UID for this component
     */
    @Serial
    private static final long serialVersionUID = -3081266863507316569L;

    /**
     * Create a new action
     */
    public GoToTaskLocation() {
        super(tr("Zoom to task"), "dialogs/autoscale/selection", tr("Zoom to the selected tasks"),
                Shortcut.registerShortcut("maproulette:zoom_to_task_selection",
                        tr("MapRoulette: Zoom to task selection"), KeyEvent.CHAR_UNDEFINED, Shortcut.NONE),
                false, "maproulette/zoom_to_task", true);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        final var locations = new ArrayDeque<BBox>();
        final var component = ((JPopupMenu) ((JMenuItem) e.getSource()).getParent()).getInvoker();
        final List<?> objects = ActionUtils.getSelectedItems(component);
        final var taskIds = new ArrayList<Long>();
        for (var obj : objects) {
            if (obj instanceof Task t && t.location() != null) {
                taskIds.add(t.id());
                for (IPrimitive p : t.geometries().allNonDeletedPrimitives()) {
                    locations.add(p.getBBox());
                }
            } else if (obj instanceof ClusteredPoint p) {
                taskIds.add(p.id());
                locations.add(p.location().getBBox());
            }
        }
        if (!locations.isEmpty()) {
            final var bbox = locations.pop();
            final var bounds = new Bounds(bbox.getBottomRight());
            bounds.extend(bbox.getTopLeft());
            while (!locations.isEmpty()) {
                final var tBBox = locations.pop();
                bounds.extend(tBBox.getTopLeft());
                bounds.extend(tBBox.getBottomRight());
            }
            final var mv = MainApplication.getMap().mapView;
            if (bounds.isCollapsed()) {
                mv.zoomTo(bounds.getMax());
                while (mv.getDist100Pixel() > 30) {
                    mv.zoomIn();
                }
            } else {
                mv.zoomTo(bounds);
            }
        }
        if (!taskIds.isEmpty()) {
            // The list's rows don't name the task's objects: the task does, and getting it may ask MapRoulette
            MainApplication.worker.execute(() -> {
                final var ids = new ArrayList<PrimitiveId>();
                for (var id : taskIds) {
                    try {
                        ids.addAll(TaskPrimitives.getPrimitiveIds(TaskCache.get(id)));
                    } catch (IOException ioException) {
                        Logging.debug("Zoom to task: no objects for task {0}: {1}", id, ioException.getMessage());
                        Logging.trace(ioException);
                    }
                }
                GuiHelper.runInEDT(() -> select(ids));
            });
        }
    }

    /**
     * Select the objects that are in the edit layer and zoom to them. Nothing is downloaded: Start does that.
     *
     * @param ids The tasks' objects
     */
    static void select(List<PrimitiveId> ids) {
        final var dataSet = MainApplication.getLayerManager().getEditDataSet();
        if (dataSet == null || ids.isEmpty()) {
            return;
        }
        final var present = ids.stream().map(dataSet::getPrimitiveById)
                .filter(p -> p != null && !p.isIncomplete() && !p.isDeleted() && p.isVisible()).toList();
        if (!present.isEmpty()) {
            dataSet.setSelected(present);
            AutoScaleAction.autoScale(AutoScaleAction.AutoScaleMode.SELECTION);
        }
    }
}
