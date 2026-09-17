package dev.subtlespark.changelines;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.diff.util.DiffUserDataKeys;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.changes.CurrentContentRevision;
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserChangeNode;
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode;
import com.intellij.openapi.vcs.changes.ui.ChangesTree;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.testFramework.LightPlatformTestCase;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.util.ui.UIUtil;
import com.intellij.vcsUtil.VcsUtil;

import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class ReviewPlatformTest extends LightPlatformTestCase {
    private final List<ReviewSession> sessions = new ArrayList<>();
    private ReviewStore.Data saved;
    private ReviewStore store;

    @Override protected void setUp() throws Exception {
        super.setUp();
        store = ApplicationManager.getApplication().getService(ReviewStore.class);
        saved = store.getState();
    }
    @Override protected void tearDown() throws Exception {
        try {
            sessions.forEach(ReviewSession::dispose);
            store.loadState(saved);
        } finally { super.tearDown(); }
    }

    public void testViewingDoesNotMarkAndMultiSelectionCanBeMarkedAndCancelled() {
        Change a = change("A.txt", "old\n", "new\n");
        Change b = change("B.txt", "before\n", "after\nextra\n");
        Fixture f = fixture("one", a, b);
        await(f.session(), a); await(f.session(), b);
        assertEquals(0, f.session().progress().reviewed());
        f.tree().setSelectionPaths(new TreePath[]{path(f.nodes().get(0)), path(f.nodes().get(1))});
        assertEquals(2, f.session().selected().size());
        assertTrue(f.session().mark(f.session().selected()));
        assertEquals(new ReviewSession.Progress(2, 2, 0, 0), f.session().progress());
        f.session().unmark(List.of(a));
        assertEquals(1, f.session().progress().reviewed());
        assertEquals(ReviewSession.Status.UNREVIEWED, f.session().status(a));
        assertEquals(ReviewSession.Status.REVIEWED, f.session().status(b));
    }

    public void testReopeningComparisonPreservesUnchangedFilesAndInvalidatesEitherChangedSide() {
        Change a = change("A.txt", "a\n", "b\n");
        Change b = change("B.txt", "x\n", "y\n");
        Fixture f = fixture("same-refs", a, b);
        await(f.session(), a); await(f.session(), b);
        assertTrue(f.session().mark(List.of(a, b)));
        f.session().dispose();
        Change same = change("A.txt", "a\n", "b\n");
        Change changed = change("B.txt", "different-base\n", "y\n");
        Fixture reopened = fixture("same-refs", same, changed);
        await(reopened.session(), same); await(reopened.session(), changed);
        assertEquals(ReviewSession.Status.REVIEWED, reopened.session().status(same));
        assertEquals(ReviewSession.Status.STALE, reopened.session().status(changed));
        assertEquals(new ReviewSession.Progress(1, 2, 0, 1), reopened.session().progress());
        Change otherSide = change("A.txt", "a\n", "new-after\n");
        Fixture newer = fixture("same-refs", otherSide);
        await(newer.session(), otherSide);
        assertEquals(ReviewSession.Status.STALE, newer.session().status(otherSide));
    }

    public void testDifferentComparisonsDoNotShareApprovalsEvenForIdenticalChanges() {
        Change a = change("A.txt", "a", "b");
        Fixture first = fixture("refs-a", a);
        Fixture second = fixture("refs-b", a);
        await(first.session(), a); await(second.session(), a);
        assertTrue(first.session().mark(List.of(a)));
        assertEquals(ReviewSession.Status.UNREVIEWED, second.session().status(a));
    }

    public void testLocalContentChangesRequireReviewAgainInsteadOfUsingCachedFingerprint() {
        AtomicReference<String> text = new AtomicReference<>("new\n");
        FilePath file = file("Live.txt");
        Change live = new Change(revision(file, "base\n", "base"), new CurrentContentRevision(file) {
            @Override public String getContent() { return text.get(); }
        });
        Fixture f = fixture("working", live);
        await(f.session(), live);
        assertTrue(f.session().mark(List.of(live)));
        text.set("different\n");
        getProject().getService(LineStatsService.class).invalidateWorkingTree();
        assertTrue(f.session().status(live) != ReviewSession.Status.REVIEWED);
        await(f.session(), live);
        assertEquals(ReviewSession.Status.STALE, f.session().status(live));
        assertTrue(f.session().mark(List.of(live)));
        assertEquals(ReviewSession.Status.REVIEWED, f.session().status(live));
    }

    public void testFailedMultiMarkDoesNotPartiallyMarkReadableFiles() {
        Change a = change("A.txt", "a", "b");
        Change missing = change("Missing.txt", null, "b");
        Fixture f = fixture("missing", a, missing);
        await(f.session(), a); await(f.session(), missing);
        assertFalse(f.session().mark(List.of(a, missing)));
        assertEquals(0, f.session().progress().reviewed());
    }

    public void testNextUnreviewedSkipsReviewedFilesWrapsAndUsesNativeDiffCallback() {
        Change a = change("A.txt", "a", "b"), b = change("B.txt", "a", "b"), c = change("C.txt", "a", "b");
        Fixture f = fixture("navigation", a, b, c);
        await(f.session(), a); await(f.session(), b); await(f.session(), c);
        assertTrue(f.session().mark(List.of(b)));
        assertSame(c, f.session().nextAfter(a));
        assertSame(a, f.session().nextAfter(c));
        AtomicInteger opened = new AtomicInteger();
        f.tree().setDoubleClickAndEnterKeyHandler(opened::incrementAndGet);
        f.session().open(c);
        UIUtil.dispatchAllInvocationEvents();
        assertSame(c, f.session().selected().getFirst());
        assertEquals(1, opened.get());
        assertTrue(f.session().mark(List.of(a, c)));
        assertNull(f.session().nextAfter(c));
    }

    public void testReviewSuffixDoesNotReplaceNativeRenderingOrDuplicateText() {
        Change a = change("A.txt", "a\n", "b\n");
        Fixture f = fixture("render", a);
        await(f.session(), a);
        assertTrue(f.session().mark(List.of(a)));
        var original = f.tree().getCellRenderer();
        var wrapper = new ChangeLinesRenderer(original, f.session()::statistics, f.session()::suffix);
        for (int i = 0; i < 2; i++) {
            Component rendered = wrapper.getTreeCellRendererComponent(f.tree(), f.nodes().getFirst(), false, false, true, 0, false);
            String text = label(rendered).getCharSequence(false).toString();
            assertTrue(text, text.endsWith("  +1  -1  已审阅"));
            assertEquals(text.indexOf("已审阅"), text.lastIndexOf("已审阅"));
        }
    }

    public void testDiffActionsAreBoundToDisplayedChangeNotCurrentTreeSelection() {
        Change a = change("A.txt", "a", "b"), b = change("B.txt", "a", "b");
        Fixture f = fixture("diff", a, b);
        await(f.session(), a); await(f.session(), b);
        var factory = DiffContentFactory.getInstance();
        var request = new SimpleDiffRequest("Diff", factory.create("a"), factory.create("b"), "left", "right");
        var nativeAction = new DumbAwareAction("Keep") {
            @Override public void actionPerformed(AnActionEvent e) { }
        };
        request.putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, List.of(nativeAction));
        ReviewDiffExtension.installActions(request, f.session(), a);
        ReviewDiffExtension.installActions(request, f.session(), a);
        var actions = request.getUserData(DiffUserDataKeys.CONTEXT_ACTIONS);
        assertEquals(3, actions.size());
        assertSame(nativeAction, actions.getFirst());
        f.tree().setSelectionPath(path(f.nodes().get(1)));
        var mark = actions.get(1);
        mark.actionPerformed(AnActionEvent.createFromAnAction(mark, null, "ChangeLinesTest", DataContext.EMPTY_CONTEXT));
        assertEquals(ReviewSession.Status.REVIEWED, f.session().status(a));
        assertEquals(ReviewSession.Status.UNREVIEWED, f.session().status(b));
        f.session().unmark(List.of(a));
        f.session().dispose();
        mark.actionPerformed(AnActionEvent.createFromAnAction(mark, null, "ChangeLinesTest", DataContext.EMPTY_CONTEXT));
        assertNull(store.fingerprint(f.session().scope(), ReviewScope.file(a)));
    }

    public void testPopupPreservesNativeItemsAndCleanupRestoresHeader() {
        Change a = change("A.txt", "a", "b");
        Fixture f = fixture("popup", a);
        await(f.session(), a);
        f.tree().setSelectionPath(path(f.nodes().getFirst()));
        var popup = new JPopupMenu();
        var nativeItem = new JMenuItem("Show Diff");
        popup.add(nativeItem);
        popup.setInvoker(f.tree());
        ReviewActions.decoratePopup(popup);
        ReviewActions.decoratePopup(popup);
        assertSame(nativeItem, popup.getComponent(0));
        assertEquals(3, popup.getComponentCount());
        assertNotNull(f.scroll().getColumnHeader());
        f.session().dispose();
        assertNull(f.tree().getClientProperty(ReviewSession.PROPERTY));
        assertTrue(f.scroll().getColumnHeader() == null || f.scroll().getColumnHeader().getView() == null);
    }

    public void testComparisonScopeRetainsRefNamesButGenericLogUsesRevisions() {
        Change first = new Change(revision(file("A.txt"), "a", "old-left"), revision(file("A.txt"), "b", "old-right"));
        Change next = new Change(revision(file("A.txt"), "a", "new-left"), revision(file("A.txt"), "b", "new-right"));
        String title = "Changes Between feature/a and main";
        String one = ReviewScope.resolve("project", "tree", "VcsChanges", title, List.of(first), "temporary:1");
        String two = ReviewScope.resolve("project", "tree", "VcsChanges", title, List.of(next), "temporary:2");
        assertEquals(one, two);
        assertFalse(one.equals(ReviewScope.resolve("project", "tree", "VcsChanges", "Changes Between feature/b and main", List.of(first), "temporary:1")));
        assertFalse(ReviewScope.resolve("project", "tree", "Version Control", "Log", List.of(first), "temporary:1")
                .equals(ReviewScope.resolve("project", "tree", "Version Control", "Log", List.of(next), "temporary:2")));
    }

    private Fixture fixture(String scope, Change... changes) {
        ChangesTree tree = new ChangesTree(getProject(), false, false) {
            @Override public void rebuildTree() { }
        };
        var root = ChangesBrowserNode.createRoot();
        List<ChangesBrowserChangeNode> nodes = new ArrayList<>();
        for (Change change : changes) {
            var node = new ChangesBrowserChangeNode(getProject(), change, null);
            root.add(node); nodes.add(node);
        }
        tree.setModel(new DefaultTreeModel(root));
        var scroll = new JScrollPane(tree);
        var session = new ReviewSession(tree, ReviewFingerprint.hash(getName(), scope));
        sessions.add(session);
        return new Fixture(tree, scroll, session, nodes);
    }

    private static LineStatsService.Result await(ReviewSession session, Change change) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            var result = session.statistics(change, true);
            if (result.state() != LineStatsService.State.LOADING) return result;
            if (SwingUtilities.isEventDispatchThread()) UIUtil.dispatchAllInvocationEvents();
            try { Thread.sleep(10); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
        }
        throw new AssertionError("Timed out waiting for review fingerprint");
    }

    private Change change(String name, String before, String after) {
        FilePath path = file(name);
        return new Change(revision(path, before, "left"), revision(path, after, "right"));
    }
    private FilePath file(String name) { return VcsUtil.getFilePath("/changelines-review-test/" + getTestName(true) + "/" + name, false); }
    private static TreePath path(ChangesBrowserChangeNode node) { return new TreePath(node.getPath()); }
    private static ContentRevision revision(FilePath path, String content, String number) {
        return new ContentRevision() {
            @Override public String getContent() { return content; }
            @Override public FilePath getFile() { return path; }
            @Override public VcsRevisionNumber getRevisionNumber() {
                return new VcsRevisionNumber() {
                    @Override public String asString() { return number; }
                    @Override public int compareTo(VcsRevisionNumber other) { return number.compareTo(other.asString()); }
                };
            }
        };
    }
    private static SimpleColoredComponent label(Component component) {
        if (component instanceof SimpleColoredComponent label) return label;
        if (component instanceof Container container) for (Component child : container.getComponents()) {
            SimpleColoredComponent label = label(child);
            if (label != null) return label;
        }
        return null;
    }
    private record Fixture(ChangesTree tree, JScrollPane scroll, ReviewSession session, List<ChangesBrowserChangeNode> nodes) { }
}
