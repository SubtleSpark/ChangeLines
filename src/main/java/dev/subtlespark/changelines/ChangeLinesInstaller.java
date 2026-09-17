package dev.subtlespark.changelines;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.vcs.changes.ui.ChangesTree;

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
    // Weak values are important: a native inner renderer can hold its enclosing tree.
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
        // Do not replace a renderer in the middle of an IDE constructor or hierarchy mutation.
        WeakReference<ChangesTree> reference = new WeakReference<>(tree);
        SwingUtilities.invokeLater(() -> {
            ChangesTree current = reference.get();
            if (disposed || current == null) return;
            if (current.isDisplayable()) attach(current);
            else detach(current);
        });
    }

    private void attach(ChangesTree tree) {
        if (disposed || tree.getProject().isDisposed() || tree.getProject().isDefault()) return;
        WeakReference<Binding> existing = bindings.get(tree);
        if (existing != null && existing.get() != null) return;
        if (tree.getCellRenderer() == null) return;
        Binding binding = new Binding(tree);
        bindings.put(tree, new WeakReference<>(binding));
        tree.addPropertyChangeListener("cellRenderer", binding.rendererListener);
        binding.wrap();
    }

    private void detach(ChangesTree tree) {
        WeakReference<Binding> reference = bindings.remove(tree);
        Binding binding = reference == null ? null : reference.get();
        if (binding != null) binding.restore();
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
        private final LineStatsService statistics;
        private final PropertyChangeListener rendererListener;
        private ChangeLinesRenderer wrapper;
        private boolean updating;

        private Binding(ChangesTree tree) {
            treeReference = new WeakReference<>(tree);
            statistics = tree.getProject().getService(LineStatsService.class);
            rendererListener = event -> { if (!updating) wrap(); };
        }

        private void wrap() {
            ChangesTree tree = treeReference.get();
            if (tree == null || tree.getProject().isDisposed()) return;
            TreeCellRenderer current = tree.getCellRenderer();
            if (current == null || current instanceof ChangeLinesRenderer) return;
            wrapper = new ChangeLinesRenderer(current, statistics::get);
            updating = true;
            try { tree.setCellRenderer(wrapper); }
            finally { updating = false; }
        }

        private void refresh() {
            ChangesTree tree = treeReference.get();
            if (tree == null || !tree.isShowing() || tree.getProject().isDisposed()
                    || wrapper == null || tree.getCellRenderer() != wrapper) return;
            // Repaint alone leaves Swing's cached row widths stale and clips the new suffix.
            // Reinstalling the same delegate invalidates those widths without changing the model/selection.
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
            if (tree == null) return;
            tree.removePropertyChangeListener("cellRenderer", rendererListener);
            if (wrapper != null && tree.getCellRenderer() == wrapper) tree.setCellRenderer(wrapper.delegate);
        }
    }
}
