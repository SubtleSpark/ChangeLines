package dev.subtlespark.changelines;

import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.ui.SimpleTextAttributes;

import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreeCellRenderer;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.util.function.BiFunction;

/** Decorates native rows; an optional paint-time wrapper keeps overflowing statistics visible. */
final class ChangeLinesRenderer implements TreeCellRenderer {
    final TreeCellRenderer delegate;
    private final BiFunction<Change, Boolean, LineStatsService.Result> statistics;
    private final BiFunction<Change, LineStatsService.Result, String> reviewSuffix;
    private final ViewportPinnedCell pinnedCell = new ViewportPinnedCell();
    private static final SimpleTextAttributes ADDED = new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN,
            JBColor.namedColor("ChangeLines.added", new JBColor(0x247A38, 0x73B97B)));
    private static final SimpleTextAttributes REMOVED = new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN,
            JBColor.namedColor("ChangeLines.removed", new JBColor(0xC62828, 0xE88989)));

    ChangeLinesRenderer(TreeCellRenderer delegate, BiFunction<Change, Boolean, LineStatsService.Result> statistics) {
        this(delegate, statistics, (change, result) -> "");
    }

    ChangeLinesRenderer(TreeCellRenderer delegate, BiFunction<Change, Boolean, LineStatsService.Result> statistics,
                        BiFunction<Change, LineStatsService.Result, String> reviewSuffix) {
        this.delegate = delegate;
        this.statistics = statistics;
        this.reviewSuffix = reviewSuffix;
    }

    @Override public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected,
            boolean expanded, boolean leaf, int row, boolean hasFocus) {
        Component component = delegate.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
        if (!(value instanceof DefaultMutableTreeNode node)) return component;
        SimpleColoredComponent label = findLabel(component);
        if (label == null) return component;
        int nativeFragments = label.getFragmentCount();
        if (node.getUserObject() instanceof Change change) {
            ContentRevision revision = change.getAfterRevision() != null ? change.getAfterRevision() : change.getBeforeRevision();
            if (revision != null && !revision.getFile().isDirectory()) appendFile(tree, row, change, label);
            else appendFolder(tree, node, label);
        } else {
            appendFolder(tree, node, label);
        }
        if (label.getFragmentCount() == nativeFragments) return component;
        return pinnedCell.configure(tree, component, label, nativeFragments);
    }

    private void appendFile(JTree tree, int row, Change change, SimpleColoredComponent label) {
        LineStatsService.Result result = statistics.apply(change, isVisibleRow(tree, row));
        if (result.state() == LineStatsService.State.READY) {
            label.append("  +" + result.stats().added(), ADDED);
            label.append("  -" + result.stats().removed(), REMOVED);
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
        String reviewed = reviewSuffix.apply(change, result);
        if (!reviewed.isEmpty()) label.append("  " + reviewed, SimpleTextAttributes.GRAYED_ATTRIBUTES);
    }

    private static void appendFolder(JTree tree, DefaultMutableTreeNode node, SimpleColoredComponent label) {
        if (!(tree.getClientProperty(ReviewSession.PROPERTY) instanceof ReviewSession session)) return;
        FolderSummary summary = session.folderSummary(node);
        if (summary == null || summary.files() == 0) return;
        if (summary.counted() > 0) {
            label.append("  +" + summary.added(), ADDED);
            label.append("  -" + summary.removed(), REMOVED);
        }
        String suffix = summary.suffix();
        if (!suffix.isEmpty()) label.append("  " + suffix, SimpleTextAttributes.GRAYED_ATTRIBUTES);
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
