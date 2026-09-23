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
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.intellij.vcsUtil.VcsUtil;

import javax.imageio.ImageIO;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Exercises the real TreeUI paint traversal, not just direct calls to our renderer. */
public final class NativePinnedTreePaintTest extends LightPlatformTestCase {
    private String savedSummaryPreference;

    @Override protected void setUp() throws Exception {
        super.setUp();
        savedSummaryPreference = PropertiesComponent.getInstance(getProject()).getValue(FolderSummarySettings.KEY);
        FolderSummarySettings.setVisible(getProject(), true);
    }

    @Override protected void tearDown() throws Exception {
        try {
            PropertiesComponent.getInstance(getProject()).setValue(FolderSummarySettings.KEY, savedSummaryPreference);
        } finally { super.tearDown(); }
    }

    public void testRealTreePaintResizesAndScrollsWithCheckboxesAndFolderSummary() throws Exception {
        Change longFile = change("ReleaseInstructionStateMachineExecutorWithAVeryLongNameForViewportRegression.txt");
        Change shortFile = change("A.txt");
        ChangesTree tree = new ChangesTree(getProject(), true, false) {
            @Override public void rebuildTree() { }
        };
        var root = ChangesBrowserNode.createRoot();
        var folder = ChangesBrowserNode.createFilePath(VcsUtil.getFilePath("/viewport-regression/module", true));
        var longNode = new ChangesBrowserChangeNode(getProject(), longFile, null);
        var shortNode = new ChangesBrowserChangeNode(getProject(), shortFile, null);
        root.add(folder);
        folder.add(longNode);
        folder.add(shortNode);
        tree.setModel(new DefaultTreeModel(root));
        var viewport = new JViewport();
        viewport.setSize(360, 180);
        viewport.setView(tree);
        viewport.setViewSize(new Dimension(2000, 300));
        tree.expandPath(new TreePath(folder.getPath()));
        tree.setSelectionPath(new TreePath(longNode.getPath()));
        var installer = ApplicationManager.getApplication().getService(ChangeLinesInstaller.class);
        installer.attach(tree);
        try {
            ReviewSession session = installer.sessionFor(longFile);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            for (Change change : List.of(longFile, shortFile)) {
                while (session.statistics(change, true).state() == LineStatsService.State.LOADING) {
                    if (System.nanoTime() > deadline) fail("Revision loading timed out");
                    UIUtil.dispatchAllInvocationEvents();
                    Thread.sleep(10);
                }
            }
            session.refresh();
            Object originalModel = tree.getModel();
            assertPinned(tree, viewport, new TreePath(longNode.getPath()), "native-tree-360");
            viewport.setSize(460, 180);
            assertPinned(tree, viewport, new TreePath(longNode.getPath()), "native-tree-460");
            viewport.setViewPosition(new Point(90, 0));
            assertPinned(tree, viewport, new TreePath(longNode.getPath()), "native-tree-scrolled");
            FolderSummarySettings.setVisible(getProject(), false);
            assertPinned(tree, viewport, new TreePath(longNode.getPath()), "native-tree-folder-summary-off");
            assertSame(originalModel, tree.getModel());
            assertEquals(0, session.progress().reviewed());
            assertEquals(2, session.progress().total());
        } finally { installer.detach(tree); }
    }

    private static void assertPinned(ChangesTree tree, JViewport viewport, TreePath path, String name) throws Exception {
        BufferedImage image = new BufferedImage(viewport.getExtentSize().width, 180, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try { viewport.paint(g); }
        finally { g.dispose(); }
        Path dir = Path.of("build/reports/pinned-sidebar");
        Files.createDirectories(dir);
        ImageIO.write(image, "png", dir.resolve(name + ".png").toFile());
        Rectangle row = tree.getPathBounds(path);
        assertNotNull(row);
        Color removed = JBColor.namedColor("ChangeLines.removed", new JBColor(0xC62828, 0xE88989));
        int rightmost = -1;
        int top = Math.max(0, row.y - viewport.getViewPosition().y);
        int bottom = Math.min(image.getHeight(), top + row.height);
        for (int y = top; y < bottom; y++) for (int x = 0; x < image.getWidth(); x++) {
            if (image.getRGB(x, y) == removed.getRGB()) rightmost = Math.max(rightmost, x);
        }
        assertTrue("Deletion count did not reach the visible edge: " + rightmost,
                rightmost >= image.getWidth() - JBUI.scale(8) - 10
                        && rightmost < image.getWidth() - JBUI.scale(8));
    }

    private Change change(String name) {
        FilePath file = VcsUtil.getFilePath("/viewport-regression/module/" + name, false);
        return new Change(revision(file, "old\n"), revision(file, "new\nextra\n"));
    }

    private ContentRevision revision(FilePath file, String text) {
        return new ContentRevision() {
            @Override public String getContent() {
                assertFalse("Revision IO must stay off EDT", SwingUtilities.isEventDispatchThread());
                return text;
            }
            @Override public FilePath getFile() { return file; }
            @Override public VcsRevisionNumber getRevisionNumber() { return VcsRevisionNumber.NULL; }
        };
    }
}
