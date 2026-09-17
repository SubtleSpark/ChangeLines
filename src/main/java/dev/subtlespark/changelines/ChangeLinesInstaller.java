package dev.subtlespark.changelines;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionPopupMenu;
import com.intellij.openapi.actionSystem.ex.ActionManagerEx;
import com.intellij.openapi.actionSystem.ex.ActionPopupMenuListener;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ui.ChangesTree;
import org.jetbrains.annotations.NotNull;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.tree.TreeCellRenderer;
import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.HierarchyEvent;
import java.beans.PropertyChangeListener;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Event-driven installation into native ChangesTree instances, not a separate tool window. */
@Service(Service.Level.APP)
public final class ChangeLinesInstaller implements Disposable {
    private final Map<ChangesTree, WeakReference<Binding>> bindings = new WeakHashMap<>();
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    private final Timer refreshTimer = new Timer(150, event -> refreshTrees());
    private final AWTEventListener hierarchyListener = this::hierarchyChanged;
    private boolean started;
    private volatile boolean disposed;

    public ChangeLinesInstaller() { refreshTimer.setRepeats(false); }

    public void start() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::start);
            return;
        }
        if (disposed || started || ApplicationManager.getApplication().isHeadlessEnvironment()) return;
        started = true;
        Toolkit.getDefaultToolkit().addAWTEventListener(hierarchyListener, AWTEvent.HIERARCHY_EVENT_MASK);
        // The callback runs after native menu items are populated, before showing it.
        // Append our submenu without replacing any IDE/third-party context actions.
        ActionManagerEx.getInstanceEx().addActionPopupMenuListener(new ActionPopupMenuListener() {
            @Override public void actionPopupMenuCreated(@NotNull ActionPopupMenu menu) {
                ReviewActions.decoratePopup(menu.getComponent());
            }
        }, this);
        for (Window window : Window.getWindows()) scan(window);
    }

    private void scan(Component component) {
        if (component instanceof ChangesTree tree) attach(tree);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) scan(child);
        }
    }

    private void hierarchyChanged(AWTEvent event) {
        if (disposed || !(event instanceof HierarchyEvent hierarchy)
                || !(hierarchy.getSource() instanceof ChangesTree tree)) return;
        if ((hierarchy.getChangeFlags() & (HierarchyEvent.DISPLAYABILITY_CHANGED
                | HierarchyEvent.SHOWING_CHANGED | HierarchyEvent.PARENT_CHANGED)) == 0) return;
        WeakReference<ChangesTree> reference = new WeakReference<>(tree);
        SwingUtilities.invokeLater(() -> {
            ChangesTree current = reference.get();
            if (disposed || current == null) return;
            if (current.isDisplayable()) attach(current);
            else detach(current);
        });
    }

    void attach(ChangesTree tree) {
        if (disposed || tree.getProject().isDisposed() || tree.getProject().isDefault()) return;
        WeakReference<Binding> reference = bindings.get(tree);
        Binding existing = reference == null ? null : reference.get();
        if (existing != null) { existing.refresh(); return; }
        if (tree.getCellRenderer() == null) return;
        Binding binding = new Binding(tree);
        bindings.put(tree, new WeakReference<>(binding));
        tree.addPropertyChangeListener("cellRenderer", binding.rendererListener);
        binding.wrap();
    }

    void detach(ChangesTree tree) {
        WeakReference<Binding> reference = bindings.remove(tree);
        Binding binding = reference == null ? null : reference.get();
        if (binding != null) binding.restore();
    }

    ReviewSession sessionFor(Change change) {
        ReviewSession found = null;
        for (WeakReference<Binding> reference : new ArrayList<>(bindings.values())) {
            Binding binding = reference.get();
            if (binding == null || !binding.reviews.contains(change)) continue;
            if (found != null && !found.scope().equals(binding.reviews.scope())) return null;
            found = binding.reviews;
        }
        return found;
    }

    public void requestRefresh() {
        if (disposed || !refreshQueued.compareAndSet(false, true)) return;
        SwingUtilities.invokeLater(() -> {
            refreshQueued.set(false);
            if (!disposed && started && !refreshTimer.isRunning()) refreshTimer.start();
        });
    }

    private void refreshTrees() {
        if (disposed) return;
        for (WeakReference<Binding> reference : new ArrayList<>(bindings.values())) {
            Binding binding = reference.get();
            if (binding != null) binding.refresh();
        }
    }

    @Override public void dispose() {
        disposed = true;
        Runnable cleanup = () -> {
            refreshTimer.stop();
            if (started) Toolkit.getDefaultToolkit().removeAWTEventListener(hierarchyListener);
            for (WeakReference<Binding> reference : new ArrayList<>(bindings.values())) {
                Binding binding = reference.get();
                if (binding != null) binding.restore();
            }
            bindings.clear();
        };
        if (SwingUtilities.isEventDispatchThread()) cleanup.run();
        else ApplicationManager.getApplication().invokeAndWait(cleanup);
    }

    private static final class Binding {
        private final WeakReference<ChangesTree> treeReference;
        private final ReviewSession reviews;
        private final PropertyChangeListener rendererListener;
        private ChangeLinesRenderer wrapper;
        private boolean updating;

        private Binding(ChangesTree tree) {
            treeReference = new WeakReference<>(tree);
            reviews = new ReviewSession(tree);
            rendererListener = event -> { if (!updating) wrap(); };
        }

        private void wrap() {
            ChangesTree tree = treeReference.get();
            if (tree == null || tree.getProject().isDisposed()) return;
            TreeCellRenderer current = tree.getCellRenderer();
            if (current == null || current instanceof ChangeLinesRenderer) return;
            wrapper = new ChangeLinesRenderer(current, reviews::statistics, reviews::suffix);
            updating = true;
            try { tree.setCellRenderer(wrapper); }
            finally { updating = false; }
        }

        private void refresh() {
            ChangesTree tree = treeReference.get();
            if (tree == null || !tree.isShowing() || tree.getProject().isDisposed()
                    || wrapper == null || tree.getCellRenderer() != wrapper) return;
            reviews.refresh();
            // Invalidate cached row widths without changing the model or selection.
            updating = true;
            try {
                tree.setCellRenderer(null);
                tree.setCellRenderer(wrapper);
            } finally { updating = false; }
            tree.revalidate();
            tree.repaint();
        }

        private void restore() {
            ChangesTree tree = treeReference.get();
            if (tree != null) {
                tree.removePropertyChangeListener("cellRenderer", rendererListener);
                if (wrapper != null && tree.getCellRenderer() == wrapper) tree.setCellRenderer(wrapper.delegate);
            }
            reviews.dispose();
        }
    }
}
