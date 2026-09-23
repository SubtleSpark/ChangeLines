package dev.subtlespark.changelines;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserChangeNode;
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode;
import com.intellij.openapi.vcs.changes.ui.ChangesTree;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.testFramework.LightPlatformTestCase;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.intellij.vcsUtil.VcsUtil;

import javax.imageio.ImageIO;
import javax.swing.CellRendererPane;
import javax.swing.Icon;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTree;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultTreeModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Pixel tests through a real CellRendererPane, plus actual IDEA ChangesTree integration. */
public final class ViewportPinnedCellTest extends LightPlatformTestCase {
    private static final Color ADDED = new Color(36, 180, 80);
    private static final Color REMOVED = new Color(220, 65, 65);
    private static final Color FILE = new Color(70, 140, 230);
    private static final Color PATH = new Color(130, 130, 130);
    private static final Color BACKGROUND = new Color(30, 33, 38);
    private static final Color SELECTED = new Color(42, 66, 107);
    private static final String LONG_PATH = "  strategyserv/src/main/java/com/atta/strategy/server/release/state/instruction/audit/machine";

    public void testShortRowStaysInlineAndKeepsNativeComponentState() throws Exception {
        Fixture f = fixture("A.java", "  src", "", 760, 20);
        Dimension before = f.cell.getPreferredSize();
        BufferedImage image = f.paint("flat-fits");
        Rectangle red = pixels(image, REMOVED);
        assertTrue("Missing deletion count", red.width > 0);
        assertTrue("Short row must not be right-aligned", red.x + red.width < 400);
        assertEquals(f.fullText, f.label.paintedText);
        assertEquals(before, f.cell.getPreferredSize());
        assertSame(f.nativePanel, f.cell.getComponent(0));
        assertSame(f.checkBox, f.nativePanel.getComponent(0));
        assertSame(f.icon, f.label.getIcon());
        assertSame(f.tag, f.label.getFragmentTag(0));
    }

    public void testLongPathPinsBothCountsAndElidesOnlyPaintedText() throws Exception {
        Fixture f = fixture("ReleaseDAO.java", LONG_PATH, "", 360, 24);
        Dimension before = f.cell.getPreferredSize();
        BufferedImage image = f.paint("flat-overflow");
        assertPinned(image, REMOVED, 360);
        assertTrue(pixels(image, ADDED).width > 0);
        assertTrue(f.label.paintedText, f.label.paintedText.startsWith("ReleaseDAO.java"));
        assertTrue(f.label.paintedText, f.label.paintedText.endsWith("…"));
        assertFalse(f.label.paintedText.contains("+11"));
        assertEquals("Full text must be restored after painting", f.fullText, f.label.getCharSequence(false).toString());
        assertEquals(before, f.cell.getPreferredSize());
        assertEquals(f.fullText, f.cell.getToolTipText());
        assertEquals(f.fullText, f.cell.getAccessibleContext().getAccessibleName());
        assertSame(f.icon, f.label.getIcon());
        assertSame(f.tag, f.label.getFragmentTag(0));
    }

    public void testResizeReturnsToInlineWithoutModelOrRendererReplacement() throws Exception {
        Fixture f = fixture("ReleaseDAO.java", LONG_PATH, "", 330, 24);
        Object model = f.tree.getModel();
        assertPinned(f.paint("resize-narrow"), REMOVED, 330);
        f.width(1600);
        BufferedImage wide = f.paint("resize-wide");
        assertEquals(f.fullText, f.label.paintedText);
        assertTrue(pixels(wide, REMOVED).x + pixels(wide, REMOVED).width < 1500);
        f.width(420);
        assertPinned(f.paint("resize-narrow-again"), REMOVED, 420);
        assertSame(model, f.tree.getModel());
    }

    public void testHorizontalScrollAndDeepIndentationUseViewportNotTreeWidth() throws Exception {
        Fixture f = fixture("VeryLongReleaseStateMachineExecutor.java", LONG_PATH, "", 360, 130);
        f.viewport.setViewPosition(new Point(80, 0));
        assertTrue("Test must actually scroll", f.viewport.getViewPosition().x > 0);
        assertPinned(f.paint("horizontal-scroll"), REMOVED, 360);
        f.viewport.setViewPosition(new Point(160, 0));
        assertPinned(f.paint("horizontal-scroll-further"), REMOVED, 360);
        assertEquals(f.fullText, f.label.getCharSequence(false).toString());
    }

    public void testSelectedAndFileBackgroundRemainNative() throws Exception {
        Fixture f = fixture("Selected.java", LONG_PATH, "", 380, 28);
        f.label.setBackground(SELECTED);
        f.nativePanel.setBackground(SELECTED);
        BufferedImage image = f.paint("selected-overflow");
        assertPinned(image, REMOVED, 380);
        assertEquals("Pinned statistics must not get a separate background rectangle",
                SELECTED.getRGB(), image.getRGB(380 - JBUI.scale(8) - 2, 1));
        Color fileBackground = new Color(39, 62, 41);
        f.label.setBackground(fileBackground);
        assertPinned(f.paint("file-background"), REMOVED, 380);
    }

    public void testNarrowPaneElidesReviewDetailsButKeepsCountsAndFullTooltip() throws Exception {
        Fixture f = fixture("ReallyLongReleaseInstructionDAO.java", LONG_PATH,
                "  部分统计 2 / 3 · 已审阅 1 / 2 · 需重审 1 · 跳过 1", 230, 24);
        BufferedImage image = f.paint("narrow-review-status");
        assertTrue(pixels(image, ADDED).width > 0);
        assertTrue(pixels(image, REMOVED).width > 0);
        assertTrue(f.cell.getToolTipText().contains("部分统计"));
        assertTrue(f.cell.getToolTipText().contains("需重审"));
        assertEquals(f.fullText, f.label.getCharSequence(false).toString());
        f.width(65); // Physically too narrow for controls + two counts: no overlap or exception.
        f.paint("extremely-narrow");
        assertEquals(f.fullText, f.label.getCharSequence(false).toString());
    }

    public void testUnicodeElisionPreservesCharactersAttributesAndTags() {
        var label = new SimpleColoredComponent();
        label.setFont(new Font("Dialog", Font.PLAIN, 14));
        Object tag = new Object();
        var attributes = new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, FILE);
        label.append("用户😀审阅文件😀很长的名字", attributes, tag);
        ViewportPinnedCell.elide(label, 1, 85);
        String clipped = label.getCharSequence(false).toString();
        assertTrue(clipped, clipped.endsWith("…"));
        for (int i = 0; i < clipped.length(); i++) {
            char c = clipped.charAt(i);
            if (Character.isHighSurrogate(c)) {
                assertTrue(i + 1 < clipped.length() && Character.isLowSurrogate(clipped.charAt(++i)));
            } else assertFalse(Character.isLowSurrogate(c));
        }
        assertSame(tag, label.getFragmentTag(0));
        var it = label.iterator();
        it.next();
        assertSame(attributes, it.getTextAttributes());
    }

    public void testNativeFileAndFolderRowsBothPinWithoutChangingReviewState() throws Exception {
        PropertiesComponent properties = PropertiesComponent.getInstance(getProject());
        String previousPreference = properties.getValue(FolderSummarySettings.KEY);
        Change change = new Change(revision("old\n"), revision("new\nextra\n"));
        ChangesTree tree = new ChangesTree(getProject(), true, false) {
            @Override public void rebuildTree() { }
        };
        var root = ChangesBrowserNode.createRoot();
        var folder = ChangesBrowserNode.createFilePath(VcsUtil.getFilePath(
                "/review/very/long/nested/path/strategyserv/src/main/java/com/atta/release/state", true));
        var node = new ChangesBrowserChangeNode(getProject(), change, null);
        root.add(folder);
        folder.add(node);
        tree.setModel(new DefaultTreeModel(root));
        var viewport = viewport(tree, 360);
        var installer = ApplicationManager.getApplication().getService(ChangeLinesInstaller.class);
        installer.attach(tree);
        try {
            FolderSummarySettings.setVisible(getProject(), true);
            ReviewSession session = installer.sessionFor(change);
            assertNotNull(session);
            await(session, change);
            assertTrue(session.mark(List.of(change)));
            session.refresh();
            Component fileCell = tree.getCellRenderer().getTreeCellRendererComponent(tree, node, true, false, true, 2, true);
            assertTrue(fileCell instanceof ViewportPinnedCell);
            SimpleColoredComponent label = findLabel(fileCell);
            assertNotNull(label);
            String full = label.getCharSequence(false).toString();
            assertTrue(full, full.contains("+2") && full.contains("-1") && full.contains("已审阅"));
            Color added = fragmentColor(label, "  +2");
            BufferedImage fileImage = paintCell(tree, viewport, fileCell, 120);
            save("native-file", fileImage);
            assertTrue("Native file counts were clipped", pixels(fileImage, added).width > 0);
            assertEquals(full, label.getCharSequence(false).toString());

            Component folderCell = tree.getCellRenderer().getTreeCellRendererComponent(tree, folder, false, false, false, 1, false);
            assertTrue(folderCell instanceof ViewportPinnedCell);
            label = findLabel(folderCell);
            full = label.getCharSequence(false).toString();
            assertTrue(full, full.contains("+2") && full.contains("-1") && full.contains("已审阅"));
            BufferedImage folderImage = paintCell(tree, viewport, folderCell, 160);
            save("native-folder", folderImage);
            assertTrue("Native folder counts were clipped", pixels(folderImage, added).width > 0);
            assertEquals(full, label.getCharSequence(false).toString());
            FolderSummarySettings.setVisible(getProject(), false);
            Component hidden = tree.getCellRenderer().getTreeCellRendererComponent(tree, folder, false, false, false, 1, false);
            assertFalse(hidden instanceof ViewportPinnedCell);
            assertFalse(findLabel(hidden).getCharSequence(false).toString().contains("  +2"));
            assertEquals(ReviewSession.Status.REVIEWED, session.status(change));
            assertEquals(1, session.progress().reviewed());
        } finally {
            installer.detach(tree);
            properties.setValue(FolderSummarySettings.KEY, previousPreference);
        }
    }

    public void testViewportListenersAreRemovedOnDetach() {
        ChangesTree tree = new ChangesTree(getProject(), false, false) {
            @Override public void rebuildTree() { }
        };
        tree.setModel(new DefaultTreeModel(ChangesBrowserNode.createRoot()));
        JViewport viewport = viewport(tree, 350);
        int original = viewport.getChangeListeners().length;
        var installer = ApplicationManager.getApplication().getService(ChangeLinesInstaller.class);
        installer.attach(tree);
        try {
            assertEquals(original + 1, viewport.getChangeListeners().length);
            installer.attach(tree);
            assertEquals("Do not duplicate viewport listeners", original + 1, viewport.getChangeListeners().length);
        } finally { installer.detach(tree); }
        assertEquals(original, viewport.getChangeListeners().length);
    }

    private ContentRevision revision(String content) {
        FilePath path = VcsUtil.getFilePath("/review/ReleaseStateMachineExecutorWithAVeryLongName.txt", false);
        return new ContentRevision() {
            @Override public String getContent() {
                assertFalse("Painting must not read revision content", SwingUtilities.isEventDispatchThread());
                return content;
            }
            @Override public FilePath getFile() { return path; }
            @Override public VcsRevisionNumber getRevisionNumber() { return VcsRevisionNumber.NULL; }
        };
    }

    private static void await(ReviewSession session, Change change) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (session.statistics(change, true).state() == LineStatsService.State.LOADING) {
            if (System.nanoTime() > deadline) fail("Statistics did not finish");
            UIUtil.dispatchAllInvocationEvents();
            Thread.sleep(10);
        }
    }

    private static Fixture fixture(String name, String path, String review, int width, int rowX) {
        RecordingLabel label = new RecordingLabel();
        label.setFont(new Font("Dialog", Font.PLAIN, 14));
        label.setBackground(BACKGROUND);
        label.setOpaque(true);
        Object tag = new Object();
        Icon icon = new Icon() {
            @Override public int getIconWidth() { return 12; }
            @Override public int getIconHeight() { return 12; }
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                g.setColor(FILE);
                g.drawRect(x, y, 10, 10);
            }
        };
        label.setIcon(icon);
        label.append(name, new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, FILE), tag);
        label.append(path, new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, PATH));
        int prefix = label.getFragmentCount();
        label.append("  +11", new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, ADDED));
        label.append("  -7", new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, REMOVED));
        if (!review.isEmpty()) label.append(review, new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, PATH));
        JPanel nativePanel = new JPanel(new BorderLayout());
        nativePanel.setOpaque(false);
        JCheckBox checkbox = new JCheckBox();
        checkbox.setOpaque(false);
        checkbox.setPreferredSize(new Dimension(22, 24));
        nativePanel.add(checkbox, BorderLayout.WEST);
        nativePanel.add(label, BorderLayout.CENTER);
        JTree tree = new JTree();
        tree.setBackground(BACKGROUND);
        JViewport viewport = viewport(tree, width);
        ViewportPinnedCell cell = new ViewportPinnedCell();
        assertSame(cell, cell.configure(tree, nativePanel, label, prefix));
        return new Fixture(tree, viewport, cell, nativePanel, label, checkbox, icon, tag,
                label.getCharSequence(false).toString(), rowX);
    }

    private static JViewport viewport(JTree tree, int width) {
        JViewport viewport = new JViewport();
        viewport.setSize(width, 80);
        viewport.setView(tree);
        viewport.setViewSize(new Dimension(4000, 300));
        return viewport;
    }

    private record Fixture(JTree tree, JViewport viewport, ViewportPinnedCell cell,
                           JPanel nativePanel, RecordingLabel label, JCheckBox checkBox,
                           Icon icon, Object tag, String fullText, int rowX) {
        void width(int width) { viewport.setSize(width, 80); }
        BufferedImage paint(String name) throws Exception {
            BufferedImage image = paintCell(tree, viewport, cell, rowX);
            save(name, image);
            return image;
        }
    }

    private static BufferedImage paintCell(JTree tree, JViewport viewport, Component cell, int rowX) {
        var pane = new CellRendererPane();
        tree.add(pane);
        int height = Math.max(24, cell.getPreferredSize().height);
        BufferedImage image = new BufferedImage(viewport.getExtentSize().width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(BACKGROUND);
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.translate(-viewport.getViewPosition().x, 0);
            pane.paintComponent(g, cell, tree, rowX, 0, cell.getPreferredSize().width, height, true);
        } finally {
            g.dispose();
            pane.removeAll();
            tree.remove(pane);
        }
        return image;
    }

    private static void assertPinned(BufferedImage image, Color color, int width) {
        Rectangle pixels = pixels(image, color);
        assertTrue("Missing pinned numeric text", pixels.width > 0);
        int right = pixels.x + pixels.width;
        assertTrue("Rightmost count at " + right + " for viewport " + width,
                right <= width - JBUI.scale(8) && right >= width - JBUI.scale(8) - 8);
    }

    private static Rectangle pixels(BufferedImage image, Color color) {
        int minX = image.getWidth(), maxX = -1, minY = image.getHeight(), maxY = -1;
        for (int x = 0; x < image.getWidth(); x++) for (int y = 0; y < image.getHeight(); y++) {
            if (image.getRGB(x, y) == color.getRGB()) {
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            }
        }
        return maxX < 0 ? new Rectangle() : new Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    private static Color fragmentColor(SimpleColoredComponent label, String text) {
        var iterator = label.iterator();
        while (iterator.hasNext()) if (iterator.next().equals(text)) return iterator.getTextAttributes().getFgColor();
        throw new AssertionError("Missing fragment: " + text);
    }

    private static SimpleColoredComponent findLabel(Component component) {
        if (component instanceof SimpleColoredComponent label) return label;
        if (component instanceof Container container) for (Component child : container.getComponents()) {
            SimpleColoredComponent label = findLabel(child);
            if (label != null) return label;
        }
        return null;
    }

    private static void save(String name, BufferedImage image) throws Exception {
        Path dir = Path.of("build/reports/pinned-sidebar");
        Files.createDirectories(dir);
        ImageIO.write(image, "png", dir.resolve(name + ".png").toFile());
    }

    private static final class RecordingLabel extends SimpleColoredComponent {
        String paintedText;
        @Override public void paint(Graphics graphics) {
            paintedText = getCharSequence(false).toString();
            super.paint(graphics);
        }
    }
}
