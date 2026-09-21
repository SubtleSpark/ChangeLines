package dev.subtlespark.changelines;

import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserChangeNode;
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode;
import com.intellij.openapi.vcs.changes.ui.ChangesTree;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.vcsUtil.VcsUtil;
import com.intellij.testFramework.LightPlatformTestCase;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.util.ui.UIUtil;

import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.Component;
import java.awt.Container;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Uses a real IDEA 2026.1 application, project, services and native ChangesTree renderer. */
public final class ChangeLinesPlatformTest extends LightPlatformTestCase {
    public void testPluginAndServicesAreLoaded() {
        var plugin = PluginManagerCore.getPlugin(PluginId.getId("dev.subtlespark.changelines"));
        assertNotNull("Plugin descriptor was not loaded", plugin);
        assertTrue("Plugin is disabled", plugin.isEnabled());
        assertNotNull(ApplicationManager.getApplication().getService(ChangeLinesInstaller.class));
        assertNotNull(getProject().getService(LineStatsService.class));
    }

    public void testNativeComparisonTreeDisplaysCountsAndPreservesRenderer() {
        AtomicBoolean readOnEdt = new AtomicBoolean();
        FilePath path = path("Sample.txt");
        Change change = new Change(revision(path, () -> {
            readOnEdt.set(readOnEdt.get() || SwingUtilities.isEventDispatchThread());
            return "old\n";
        }), revision(path, () -> {
            readOnEdt.set(readOnEdt.get() || SwingUtilities.isEventDispatchThread());
            return "new\nextra\n";
        }));
        assertEquals(new LineDiff.Stats(2, 1), await(change).stats());
        assertFalse("Revision I/O ran on the UI thread", readOnEdt.get());

        ChangesTree tree = new ChangesTree(getProject(), true, false) {
            @Override public void rebuildTree() {}
        };
        var node = new ChangesBrowserChangeNode(getProject(), change, null);
        var root = ChangesBrowserNode.createRoot();
        root.add(node);
        tree.setModel(new DefaultTreeModel(root));
        var original = tree.getCellRenderer();
        Component originalComponent = original.getTreeCellRendererComponent(tree, node, false, false, true, 0, false);
        var wrapper = new ChangeLinesRenderer(original, service()::get);
        tree.setCellRenderer(wrapper);
        try {
            for (int i = 0; i < 2; i++) {
                Component rendered = wrapper.getTreeCellRendererComponent(tree, node, false, false, true, 0, false);
                assertSame("Native checkbox/icon container must not be replaced", originalComponent, rendered);
                SimpleColoredComponent label = label(rendered);
                assertNotNull("Native renderer label was not found", label);
                String text = label.getCharSequence(false).toString();
                assertTrue(text, text.contains("Sample.txt"));
                assertTrue(text, text.endsWith("  +2  -1"));
                assertEquals("Counts were appended twice", text.indexOf("  +2"), text.lastIndexOf("  +2"));
            }
        } finally {
            tree.setCellRenderer(original);
        }
    }

    public void testSameFileDifferentComparisonsDoNotShareCache() {
        FilePath path = path("Shared.txt");
        Change first = new Change(revision(path, () -> "a\n"), revision(path, () -> "a\nb\n"));
        Change second = new Change(revision(path, () -> "a\nb\nc\n"), revision(path, () -> "a\n"));
        assertEquals(new LineDiff.Stats(1, 0), await(first).stats());
        assertEquals(new LineDiff.Stats(0, 2), await(second).stats());
        assertEquals(new LineDiff.Stats(1, 0), service().get(first, false).stats());
    }

    public void testMissingRevisionContentIsNotReportedAsZero() {
        FilePath path = path("Missing.txt");
        Change change = new Change(revision(path, () -> null), revision(path, () -> "a\n"));
        assertEquals(LineStatsService.State.UNAVAILABLE, await(change).state());
        assertNull(service().get(change, false).stats());
    }

    public void testBinaryContentIsNotReportedAsZero() {
        Change change = new Change(null, revision(path("Binary.txt"), () -> "a\0b"));
        assertEquals(LineStatsService.State.BINARY, await(change).state());
    }

    public void testCancellationCanBeRetried() {
        AtomicInteger attempts = new AtomicInteger();
        Change change = new Change(null, revision(path("Cancelled.txt"), () -> {
            if (attempts.incrementAndGet() == 1) throw new ProcessCanceledException();
            return "a\n";
        }));
        assertEquals(new LineDiff.Stats(1, 0), await(change).stats());
        assertTrue(attempts.get() >= 2);
    }

    public void testLargeContentProducesLimitedState() {
        Change change = new Change(null, revision(path("Large.txt"), () -> "x".repeat(LineDiff.MAX_CHARS + 1)));
        assertEquals(LineStatsService.State.LIMITED, await(change).state());
    }

    public void testNonChangeRowsDoNotRequestStatistics() {
        AtomicInteger requests = new AtomicInteger();
        var original = new javax.swing.tree.DefaultTreeCellRenderer();
        var wrapper = new ChangeLinesRenderer(original, (change, visible) -> {
            requests.incrementAndGet();
            return LineStatsService.Result.of(LineStatsService.State.LOADING);
        });
        var tree = new JTree();
        var node = new DefaultMutableTreeNode("directory");
        wrapper.getTreeCellRendererComponent(tree, node, false, false, false, 0, false);
        assertEquals(0, requests.get());
    }

    private LineStatsService service() { return getProject().getService(LineStatsService.class); }

    private LineStatsService.Result await(Change change) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            LineStatsService.Result result = service().get(change, true);
            if (result.state() != LineStatsService.State.LOADING) return result;
            if (SwingUtilities.isEventDispatchThread()) UIUtil.dispatchAllInvocationEvents();
            try { Thread.sleep(10); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        }
        throw new AssertionError("Background line statistics did not complete within 15 seconds");
    }

    private FilePath path(String name) {
        return VcsUtil.getFilePath("/changelines-test/" + getTestName(true) + "/" + name, false);
    }

    private static ContentRevision revision(FilePath path, Supplier<String> content) {
        return new ContentRevision() {
            @Override public String getContent() { return content.get(); }
            @Override public FilePath getFile() { return path; }
            @Override public VcsRevisionNumber getRevisionNumber() { return VcsRevisionNumber.NULL; }
        };
    }

    private static SimpleColoredComponent label(Component component) {
        if (component instanceof SimpleColoredComponent label) return label;
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                SimpleColoredComponent found = label(child);
                if (found != null) return found;
            }
        }
        return null;
    }
}
