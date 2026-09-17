package dev.subtlespark.changelines;

import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.ui.SimpleTextAttributes;

import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreeCellRenderer;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.util.function.BiFunction;

/** Decorates the original component, preserving native icons, checkboxes and issue links. */
final class ChangeLinesRenderer implements TreeCellRenderer {
    final TreeCellRenderer delegate;
    private final BiFunction<Change, Boolean, LineStatsService.Result> statistics;

    ChangeLinesRenderer(TreeCellRenderer delegate, BiFunction<Change, Boolean, LineStatsService.Result> statistics) {
        this.delegate = delegate;
        this.statistics = statistics;
    }

    @Override public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected,
            boolean expanded, boolean leaf, int row, boolean hasFocus) {
        Component component = delegate.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
        if (!(value instanceof DefaultMutableTreeNode node) || !(node.getUserObject() instanceof Change change)) {
            return component;
        }
        ContentRevision revision = change.getAfterRevision() != null ? change.getAfterRevision() : change.getBeforeRevision();
        if (revision == null || revision.getFile().isDirectory()) return component;
        SimpleColoredComponent label = findLabel(component);
        if (label == null) return component; // A third-party renderer may not expose a compatible label.
        LineStatsService.Result result = statistics.apply(change, isVisibleRow(tree, row));
        if (result.state() == LineStatsService.State.READY) {
            // Read the IDE's built-in VCS colors on every render, just like native file names.
            // Do not cache RGB values or introduce plugin-specific theme keys. A null color
            // inherits the label foreground; the native renderer owns selection contrast.
            label.append("  +" + result.stats().added(), attributes(FileStatus.ADDED));
            label.append("  -" + result.stats().removed(), attributes(FileStatus.DELETED));
        } else {
            String status = switch (result.state()) {
                case LOADING -> "…";
                case BINARY -> "binary";
                case LIMITED -> "too large";
                case UNAVAILABLE -> "unavailable";
                default -> "";
            };
            label.append("  (" + status + ")", SimpleTextAttributes.GRAYED_ATTRIBUTES);
        }
        return component;
    }

    private static SimpleTextAttributes attributes(FileStatus status) {
        return new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, status.getColor());
    }

    private static boolean isVisibleRow(JTree tree, int row) {
        if (!tree.isShowing() || row < 0) return false;
        int height = tree.getRowHeight();
        if (height <= 0) return true;
        Rectangle visible = tree.getVisibleRect();
        long top = (long) row * height + tree.getInsets().top;
        return top + height >= visible.y && top <= (long) visible.y + visible.height;
    }

    private static SimpleColoredComponent findLabel(Component component) {
        if (component instanceof SimpleColoredComponent label) return label;
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                SimpleColoredComponent label = findLabel(child);
                if (label != null) return label;
            }
        }
        return null;
    }
}
