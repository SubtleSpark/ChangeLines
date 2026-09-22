package dev.subtlespark.changelines;

import com.intellij.ui.SimpleColoredComponent;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.util.ui.JBUI;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/**
 * Paint-time layout only: keep the native renderer and its natural preferred size.
 * Querying row bounds from a cell renderer would recursively enter the tree layout;
 * at paint time the CellRendererPane has already assigned the actual row position.
 */
final class ViewportPinnedCell extends JPanel {
    private record Fragment(String text, SimpleTextAttributes attributes) { }

    private final SimpleColoredComponent suffix = new SimpleColoredComponent();
    private JTree tree;
    private Component nativeComponent;
    private SimpleColoredComponent label;
    private Dimension naturalSize = new Dimension();
    private List<Fragment> fragments = List.of();
    private int nativeFragmentCount;

    ViewportPinnedCell() {
        super(null);
        setOpaque(false);
        suffix.setOpaque(false);
    }

    Component configure(JTree tree, Component component, SimpleColoredComponent label, int nativeFragmentCount) {
        // Standalone renderer measurements and expanded-item popups keep the natural text.
        JViewport viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, tree);
        if (viewport == null || viewport.getExtentSize().width <= 0
                || !tree.getComponentOrientation().isLeftToRight() || label.isIconOnTheRight()) return component;
        this.tree = tree;
        this.label = label;
        this.nativeFragmentCount = nativeFragmentCount;
        naturalSize = new Dimension(component.getPreferredSize());
        fragments = snapshot(label);
        if (nativeComponent != component || component.getParent() != this) {
            removeAll();
            nativeComponent = component;
            add(component);
        }
        setBackground(tree.getBackground());
        String fullText = label.getCharSequence(false).toString();
        String nativeTooltip = component instanceof JComponent c ? c.getToolTipText() : null;
        setToolTipText(nativeTooltip == null ? fullText : nativeTooltip);
        getAccessibleContext().setAccessibleName(fullText);
        return this;
    }

    @Override public Dimension getPreferredSize() { return new Dimension(naturalSize); }

    @Override public void doLayout() {
        if (nativeComponent == null) return;
        nativeComponent.setBounds(0, 0, getWidth(), getHeight());
        layoutChildren(nativeComponent);
    }

    @Override public void paint(Graphics g) {
        doLayout();
        // Do not pin an expanded-item tooltip or a renderer painted outside this tree.
        if (tree == null || !SwingUtilities.isDescendingFrom(this, tree)) {
            super.paint(g);
            return;
        }
        Rectangle visible = tree.getVisibleRect();
        if (visible.width <= 0) { super.paint(g); return; }
        int rowX = SwingUtilities.convertPoint(this, 0, 0, tree).x;
        int right = Math.min(getWidth(), usableRight(tree, visible) - rowX - JBUI.scale(8));
        if (naturalSize.width <= right) { super.paint(g); return; }

        Point origin = SwingUtilities.convertPoint(label, 0, 0, this);
        int controlsEnd = origin.x + textStart(label);
        int suffixSpace = right - Math.max(visible.x - rowX, controlsEnd);
        if (suffixSpace <= 0) { super.paint(g); return; }

        prepareSuffix();
        // Very narrow panes elide trailing review details before touching the numeric counts.
        // The full native text and statistics remain in the tooltip/accessibility name.
        elide(suffix, suffix.getFragmentCount(), suffixSpace);
        int suffixWidth = suffix.getPreferredSize().width;
        if (suffixWidth > suffixSpace || suffix.getCharSequence(false).isEmpty()) {
            super.paint(g);
            return;
        }
        int suffixX = right - suffixWidth;
        try {
            var iterator = label.iterator();
            for (int i = 0; iterator.hasNext(); i++) {
                iterator.next();
                if (i >= nativeFragmentCount) iterator.setFragment("");
            }
            elide(label, nativeFragmentCount, Math.max(0, suffixX - JBUI.scale(6) - origin.x));
            // Native renderer still paints the checkbox, icon, selection, file background,
            // retained speed-search fragments and text. No custom background color is used.
            super.paint(g);
        } finally {
            restoreText(label, fragments);
        }
        suffix.setSize(suffixWidth, label.getHeight());
        Graphics copy = g.create(suffixX, origin.y, suffixWidth, label.getHeight());
        try { suffix.paint(copy); }
        finally { copy.dispose(); }
    }

    private void prepareSuffix() {
        suffix.clear();
        suffix.setFont(label.getFont());
        suffix.setForeground(label.getForeground());
        suffix.setEnabled(label.isEnabled());
        Insets ipad = label.getIpad();
        suffix.setIpad(new Insets(ipad.top, 0, ipad.bottom, 0));
        Border border = label.getMyBorder();
        Insets inner = border == null ? new Insets(0, 0, 0, 0) : border.getBorderInsets(label);
        suffix.setMyBorder(new EmptyBorder(inner.top, 0, inner.bottom, 0));
        Insets outer = label.getInsets();
        suffix.setBorder(new EmptyBorder(outer.top, 0, outer.bottom, 0));
        for (int i = nativeFragmentCount; i < fragments.size(); i++) {
            Fragment fragment = fragments.get(i);
            String text = i == nativeFragmentCount ? fragment.text().stripLeading() : fragment.text();
            suffix.append(text, fragment.attributes());
        }
    }

    /** End-elide in place without rebuilding fragments, so native attributes/tags/icons survive. */
    static void elide(SimpleColoredComponent component, int fragmentCount, int width) {
        if (component.getPreferredSize().width <= width) return;
        for (int i = fragmentCount - 1; i >= 0; i--) {
            var iterator = component.iterator(i);
            String text = iterator.next();
            if (text.isEmpty()) continue;
            iterator.setFragment("…");
            if (component.getPreferredSize().width <= width) {
                int low = 0, high = text.codePointCount(0, text.length());
                while (low < high) {
                    int mid = (low + high + 1) >>> 1;
                    iterator.setFragment(text.substring(0, text.offsetByCodePoints(0, mid)) + "…");
                    if (component.getPreferredSize().width <= width) low = mid;
                    else high = mid - 1;
                }
                iterator.setFragment(text.substring(0, text.offsetByCodePoints(0, low)) + "…");
                return;
            }
            iterator.setFragment("");
        }
    }

    private static int textStart(SimpleColoredComponent label) {
        int width = label.getIpad().left + label.getInsets().left;
        if (label.getMyBorder() != null) width += label.getMyBorder().getBorderInsets(label).left;
        if (label.getIcon() != null) width += label.getIcon().getIconWidth() + label.getIconTextGap();
        return width;
    }

    private static int usableRight(JTree tree, Rectangle visible) {
        int right = visible.x + visible.width;
        JScrollPane pane = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, tree);
        if (pane != null) {
            JScrollBar bar = pane.getVerticalScrollBar();
            if (bar != null && bar.isVisible() && bar.getWidth() > 0) {
                Rectangle bounds = SwingUtilities.convertRectangle(bar.getParent(), bar.getBounds(), tree);
                // Standard scrollbars are outside the viewport; macOS overlay scrollbars may not be.
                if (bounds.intersects(visible) && bounds.x > visible.x) right = Math.min(right, bounds.x);
            }
        }
        return right;
    }

    private static List<Fragment> snapshot(SimpleColoredComponent component) {
        List<Fragment> result = new ArrayList<>();
        var iterator = component.iterator();
        while (iterator.hasNext()) result.add(new Fragment(iterator.next(), iterator.getTextAttributes()));
        return List.copyOf(result);
    }

    private static void restoreText(SimpleColoredComponent component, List<Fragment> fragments) {
        var iterator = component.iterator();
        for (Fragment fragment : fragments) {
            if (!iterator.hasNext()) break;
            iterator.next();
            iterator.setFragment(fragment.text());
        }
    }

    private static void layoutChildren(Component component) {
        if (component instanceof Container container) {
            container.doLayout();
            for (Component child : container.getComponents()) layoutChildren(child);
        }
    }
}
