package dev.subtlespark.changelines;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.diff.util.DiffUserDataKeys;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.changes.CurrentContentRevision;
import com.intellij.openapi.vcs.changes.actions.diff.ChangeDiffRequestProducer;
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserBase;
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserChangeNode;
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode;
import com.intellij.openapi.vcs.changes.ui.ChangesTree;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.openapi.vcs.impl.ChangesBrowserToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.testFramework.LightPlatformTestCase;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.ui.content.ContentFactory;
import com.intellij.util.ui.UIUtil;
import com.intellij.vcsUtil.VcsUtil;

import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Regression tests for the user's Changes Between workflow, not Project View decoration. */
public final class ReviewWorkflowTest extends LightPlatformTestCase {
    private final List<ReviewSession> sessions = new ArrayList<>();
    private final List<ChangesTree> attached = new ArrayList<>();
    private ChangeLinesInstaller installer;
    private ReviewStore store;
    private ReviewStore.Data saved;
    private boolean openedToolWindow;

    @Override protected void setUp() throws Exception {
        super.setUp();
        installer = ApplicationManager.getApplication().getService(ChangeLinesInstaller.class);
        store = ApplicationManager.getApplication().getService(ReviewStore.class);
        saved = store.getState();
    }

    @Override protected void tearDown() throws Exception {
        try {
            attached.forEach(installer::detach);
            sessions.forEach(ReviewSession::dispose);
            if (openedToolWindow) {
                var manager = ToolWindowManager.getInstance(getProject());
                var window = manager.getToolWindow("VcsChanges");
                if (window != null) window.getContentManager().removeAllContents(true);
                manager.unregisterToolWindow("VcsChanges");
            }
            store.loadState(saved);
        } finally { super.tearDown(); }
    }

    public void testActualChangesBetweenWindowWorksWithNoLocalChanges() {
        assertTrue(ChangeListManager.getInstance(getProject()).getAllChanges().isEmpty());
        Change modified = change("Sample.txt", "old\n", "new\nextra\n");
        Change deleted = new Change(revision(file("Deleted.txt"), () -> "gone\n", "left"), null);
        NativeBrowser browser = new NativeBrowser(getProject(), List.of(modified, deleted));
        var content = ContentFactory.getInstance().createContent(browser, "Changes Between release-A and release-B", true);
        openedToolWindow = true;
        ChangesBrowserToolWindow.showTab(getProject(), content);
        ChangesTree tree = browser.getViewer();
        attached.add(tree);
        int nativeCount = browser.getToolbar().getActionGroup().getChildren(null).length;
        var originalRenderer = tree.getCellRenderer();
        installer.scan(browser); // exercise discovery from a native container, not direct attach only
        var session = (ReviewSession) tree.getClientProperty(ReviewSession.PROPERTY);
        assertNotNull("The actual comparison tree must receive a review session", session);
        assertTrue(tree.getCellRenderer() instanceof ChangeLinesRenderer);
        session.refresh();
        assertFalse("Changes Between must have a persistent identity", session.scope().startsWith("temporary:"));
        await(session, modified); await(session, deleted);
        assertEquals(new LineDiff.Stats(2, 1), session.statistics(modified, false).stats());
        assertEquals(new LineDiff.Stats(0, 1), session.statistics(deleted, false).stats());
        assertTrue(Arrays.asList(browser.getToolbar().getActionGroup().getChildren(null)).contains(session.toolbarActions()));
        installer.scan(browser);
        assertEquals("Repeated discovery must not duplicate native controls", nativeCount + 1,
                browser.getToolbar().getActionGroup().getChildren(null).length);
        assertTrue(rendered(tree, modified).endsWith("  +2  -1"));
        assertTrue(session.mark(List.of(modified, deleted)));
        assertEquals(new ReviewSession.Progress(2, 2, 0, 0), session.progress());
        assertTrue(rendered(tree, modified).endsWith("  +2  -1  已审阅"));
        session.unmark(List.of(deleted));
        session.open(deleted);
        UIUtil.dispatchAllInvocationEvents();
        assertSame("Next must reuse the comparison browser's native callback", deleted, browser.opened);
        installer.detach(tree);
        assertSame(originalRenderer, tree.getCellRenderer());
        assertEquals(nativeCount, browser.getToolbar().getActionGroup().getChildren(null).length);
    }

    public void testExistingHeaderAndSessionAreRestoredOnDetach() {
        Change change = change("A.txt", "a", "b");
        ChangesTree tree = tree(change);
        attached.add(tree);
        var original = tree.getCellRenderer();
        var scroll = new JScrollPane(tree);
        var previous = new JLabel("native header");
        scroll.setColumnHeaderView(previous);
        installer.scan(scroll);
        var session = (ReviewSession) tree.getClientProperty(ReviewSession.PROPERTY);
        assertNotNull(session);
        assertNotSame(previous, scroll.getColumnHeader().getView());
        installer.scan(scroll);
        assertSame(session, tree.getClientProperty(ReviewSession.PROPERTY));
        installer.detach(tree);
        assertSame(previous, scroll.getColumnHeader().getView());
        assertSame(original, tree.getCellRenderer());
        assertNull(tree.getClientProperty(ReviewSession.PROPERTY));
    }

    public void testViewingDoesNotMarkAndMultiSelectionIsAtomic() {
        Change a = change("A.txt", "a", "b"), b = change("B.txt", "x", "y");
        ReviewSession session = fixture("batch", a, b);
        await(session, a); await(session, b);
        assertEquals(0, session.progress().reviewed());
        assertTrue(session.mark(List.of(a, b)));
        assertEquals(new ReviewSession.Progress(2, 2, 0, 0), session.progress());
        session.unmark(List.of(a));
        assertEquals(ReviewSession.Status.UNREVIEWED, session.status(a));
        assertEquals(ReviewSession.Status.REVIEWED, session.status(b));
        Change missing = change("Missing.txt", null, "x");
        ReviewSession other = fixture("missing", a, missing);
        await(other, a); await(other, missing);
        assertFalse(other.mark(List.of(a, missing)));
        assertEquals(0, other.progress().reviewed());
    }

    public void testReopeningPreservesUnchangedFilesAndInvalidatesBothSides() {
        Change a = change("A.txt", "a", "b"), b = change("B.txt", "x", "y");
        ReviewSession first = fixture("same", a, b);
        await(first, a); await(first, b);
        assertTrue(first.mark(List.of(a, b)));
        first.dispose();
        Change same = change("A.txt", "a", "b"), changedBase = change("B.txt", "new base", "y");
        ReviewSession reopened = fixture("same", same, changedBase);
        await(reopened, same); await(reopened, changedBase);
        assertEquals(ReviewSession.Status.REVIEWED, reopened.status(same));
        assertEquals(ReviewSession.Status.STALE, reopened.status(changedBase));
        Change changedAfter = change("A.txt", "a", "new after");
        ReviewSession after = fixture("same", changedAfter);
        await(after, changedAfter);
        assertEquals(ReviewSession.Status.STALE, after.status(changedAfter));
        assertTrue(after.mark(List.of(changedAfter)));
        assertEquals(ReviewSession.Status.REVIEWED, after.status(changedAfter));
    }

    public void testComparisonsAndResetDoNotShareMarks() {
        Change change = change("A.txt", "a", "b");
        ReviewSession first = fixture("first", change), second = fixture("second", change);
        await(first, change); await(second, change);
        assertTrue(first.mark(List.of(change)));
        assertEquals(ReviewSession.Status.UNREVIEWED, second.status(change));
        assertTrue(second.mark(List.of(change)));
        first.unmark(first.changes());
        assertEquals(ReviewSession.Status.REVIEWED, second.status(change));
    }

    public void testWorkingCopyInvalidationCannotReapproveAnOldFingerprint() {
        AtomicReference<String> text = new AtomicReference<>("new\n");
        FilePath file = file("Live.txt");
        Change live = new Change(revision(file, () -> "base\n", "left"), new CurrentContentRevision(file) {
            @Override public String getContent() { return text.get(); }
        });
        ReviewSession session = fixture("live", live);
        await(session, live);
        assertTrue(session.mark(List.of(live)));
        text.set("changed again\n");
        getProject().getService(LineStatsService.class).invalidateWorkingTree();
        assertFalse("Old cached contents must not be marked while the new contents are pending", session.mark(List.of(live)));
        assertNotSame(ReviewSession.Status.REVIEWED, session.cachedStatus(live));
        await(session, live);
        assertEquals(ReviewSession.Status.STALE, session.status(live));
        assertTrue(session.mark(List.of(live)));
    }

    public void testBinaryAndUnavailableFilesAreSkippedButDeletedFilesAreReviewable() {
        Change text = change("A.txt", "a", "b");
        Change binary = change("Binary.txt", "a", "a\0b");
        Change missing = change("Missing.txt", null, "after");
        Change deleted = new Change(revision(file("Deleted.txt"), () -> "one\ntwo\n", "left"), null);
        ReviewSession session = fixture("skip", text, binary, missing, deleted);
        for (Change c : session.changes()) await(session, c);
        assertEquals(ReviewSession.Status.UNAVAILABLE, session.status(binary));
        assertEquals(ReviewSession.Status.UNAVAILABLE, session.status(missing));
        assertEquals(2, session.progress().total());
        assertTrue(session.progressText(), session.progressText().contains("跳过 2"));
        assertTrue(session.mark(List.of(text)));
        assertSame(deleted, session.nextAfter(text));
        assertEquals(new LineDiff.Stats(0, 2), session.statistics(deleted, false).stats());
        assertTrue(session.mark(List.of(deleted)));
        assertEquals(2, session.progress().reviewed());
        assertNull(session.nextAfter(text));
    }

    public void testRenameUsesActualBeforeAndAfterPaths() {
        Change rename = new Change(revision(file("Before.txt"), () -> "same\n", "left"),
                revision(file("After.txt"), () -> "same\n", "right"));
        ReviewSession session = fixture("rename", rename);
        await(session, rename);
        assertEquals(new LineDiff.Stats(0, 0), session.statistics(rename, false).stats());
        assertTrue(session.mark(List.of(rename)));
        assertEquals(1, session.progress().reviewed());
    }

    public void testNativeToolbarActionsUseTheirOwnTreeNotFocusedWindow() {
        Change a = change("A.txt", "a", "b"), b = change("B.txt", "x", "y");
        ChangesTree tree = tree(a, b);
        var session = new ReviewSession(tree, ReviewFingerprint.hash(getName(), "toolbar"));
        sessions.add(session);
        await(session, a); await(session, b);
        select(tree, a);
        AnAction toggle = session.toolbarActions().getChildren(null)[1];
        invoke(toggle);
        assertEquals(ReviewSession.Status.REVIEWED, session.status(a));
        assertEquals(ReviewSession.Status.UNREVIEWED, session.status(b));
        invoke(toggle);
        assertEquals(ReviewSession.Status.UNREVIEWED, session.status(a));
    }

    public void testRegisteredDiffExtensionMarksDisplayedFileNotTreeSelection() {
        Change a = change("A.txt", "a", "b"), b = change("B.txt", "x", "y");
        ChangesTree tree = tree(a, b);
        attached.add(tree);
        new JScrollPane(tree);
        installer.scan(tree);
        ReviewSession session = installer.sessionFor(a);
        await(session, a); await(session, b);
        var factory = DiffContentFactory.getInstance();
        var request = new SimpleDiffRequest("Diff", factory.create("a"), factory.create("b"), "left", "right");
        request.putUserData(ChangeDiffRequestProducer.CHANGE_KEY, a);
        var parent = Disposer.newDisposable("ChangeLines Diff test");
        try {
            var panel = DiffManager.getInstance().createRequestPanel(getProject(), parent, null);
            panel.setRequest(request);
            waitFor(() -> request.getUserData(DiffUserDataKeys.CONTEXT_ACTIONS) != null);
            var actions = request.getUserData(DiffUserDataKeys.CONTEXT_ACTIONS);
            assertEquals(2, actions.stream().filter(x -> x instanceof ReviewActions.BoundAction).count());
            select(tree, b);
            AnAction mark = actions.stream().filter(x -> x instanceof ReviewActions.BoundAction).findFirst().orElseThrow();
            invoke(mark);
            assertEquals(ReviewSession.Status.REVIEWED, session.status(a));
            assertEquals(ReviewSession.Status.UNREVIEWED, session.status(b));
        } finally { Disposer.dispose(parent); }
    }

    public void testReplacementModelDisablesOldDiffAndQueuedNavigation() {
        Change a = change("A.txt", "a", "b");
        ChangesTree tree = tree(a);
        ReviewSession session = new ReviewSession(tree, ReviewFingerprint.hash(getName()));
        sessions.add(session);
        await(session, a);
        var action = new ReviewActions.BoundAction(session, a, ReviewActions.Kind.TOGGLE);
        AtomicInteger opened = new AtomicInteger();
        tree.setDoubleClickAndEnterKeyHandler(opened::incrementAndGet);
        session.open(a);
        Change replacement = change("A.txt", "a", "different");
        tree.setModel(model(getProject(), List.of(replacement)));
        select(tree, replacement);
        UIUtil.dispatchAllInvocationEvents();
        assertEquals(0, opened.get());
        invoke(action);
        assertEquals(ReviewSession.Status.UNREVIEWED, session.status(replacement));
    }

    public void testAmbiguousUnknownDiffContextIsNotGuessed() {
        FilePath path = file("Unknown.txt");
        ContentRevision before = revision(path, () -> "a", null), after = revision(path, () -> "b", null);
        Change shared = new Change(before, after);
        ChangesTree first = tree(shared), second = tree(shared);
        attached.add(first); attached.add(second);
        installer.scan(first);
        assertNotNull(installer.sessionFor(shared));
        installer.scan(second);
        assertNull(installer.sessionFor(shared));
    }

    public void testRendererAndActionUpdatesDoNotLoadRevisionContentOnEdt() {
        AtomicBoolean onEdt = new AtomicBoolean();
        AtomicInteger reads = new AtomicInteger();
        Supplier<String> text = () -> {
            if (SwingUtilities.isEventDispatchThread()) onEdt.set(true);
            reads.incrementAndGet();
            return "a\n";
        };
        Change change = new Change(null, revision(file("Counted.txt"), text, "right"));
        ReviewSession session = fixture("update", change);
        await(session, change);
        int count = reads.get();
        var action = new ReviewActions.BoundAction(session, change, ReviewActions.Kind.TOGGLE);
        for (int i = 0; i < 100; i++) action.update(event(action));
        assertEquals(count, reads.get());
        assertFalse(onEdt.get());
    }

    public void testComparisonIdentityKeepsRefPairAndGenericLogSeparatesRevisions() {
        Change first = new Change(revision(file("A.txt"), () -> "a", "old-left"), revision(file("A.txt"), () -> "b", "old-right"));
        Change next = new Change(revision(file("A.txt"), () -> "a", "new-left"), revision(file("A.txt"), () -> "b", "new-right"));
        String title = "Changes Between feature/a and main";
        assertEquals(ReviewScope.resolve("project", "tree", "VcsChanges", title, List.of(first), "temporary:1"),
                ReviewScope.resolve("project", "tree", "VcsChanges", title, List.of(next), "temporary:2"));
        assertFalse(ReviewScope.resolve("project", "tree", "VcsChanges", title, List.of(first), "temporary:1")
                .equals(ReviewScope.resolve("project", "tree", "VcsChanges", "Changes Between feature/b and main", List.of(first), "temporary:1")));
        assertFalse(ReviewScope.resolve("project", "tree", "Version Control", "Log", List.of(first), "temporary:1")
                .equals(ReviewScope.resolve("project", "tree", "Version Control", "Log", List.of(next), "temporary:2")));
    }

    private ReviewSession fixture(String scope, Change... changes) {
        ChangesTree tree = tree(changes);
        new JScrollPane(tree);
        ReviewSession session = new ReviewSession(tree, ReviewFingerprint.hash(getName(), scope));
        sessions.add(session);
        return session;
    }

    private ChangesTree tree(Change... changes) {
        ChangesTree tree = new ChangesTree(getProject(), false, false) {
            @Override public void rebuildTree() { }
        };
        tree.setModel(model(getProject(), List.of(changes)));
        return tree;
    }

    private static DefaultTreeModel model(Project project, List<Change> changes) {
        var root = ChangesBrowserNode.createRoot();
        for (Change c : changes) root.add(new ChangesBrowserChangeNode(project, c, null));
        return new DefaultTreeModel(root);
    }

    private static void select(ChangesTree tree, Change change) {
        var root = (DefaultMutableTreeNode) tree.getModel().getRoot();
        var nodes = root.preorderEnumeration();
        while (nodes.hasMoreElements()) {
            var node = (DefaultMutableTreeNode) nodes.nextElement();
            if (node.getUserObject() == change) { tree.setSelectionPath(new TreePath(node.getPath())); return; }
        }
        throw new AssertionError("Change is not in the tree");
    }

    private static String rendered(ChangesTree tree, Change change) {
        select(tree, change);
        Object node = tree.getSelectionPath().getLastPathComponent();
        Component component = tree.getCellRenderer().getTreeCellRendererComponent(tree, node, false, false, true, 0, false);
        return label(component).getCharSequence(false).toString();
    }

    private static SimpleColoredComponent label(Component component) {
        if (component instanceof SimpleColoredComponent label) return label;
        if (component instanceof Container container) for (Component child : container.getComponents()) {
            SimpleColoredComponent label = label(child);
            if (label != null) return label;
        }
        return null;
    }

    private static AnActionEvent event(AnAction action) {
        return AnActionEvent.createFromAnAction(action, null, "ChangeLinesTest", DataContext.EMPTY_CONTEXT);
    }
    private static void invoke(AnAction action) { action.actionPerformed(event(action)); }
    private static void await(ReviewSession session, Change change) {
        waitFor(() -> session.statistics(change, true).state() != LineStatsService.State.LOADING);
    }
    private static void waitFor(BooleanSupplier done) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (done.getAsBoolean()) return;
            UIUtil.dispatchAllInvocationEvents();
            try { Thread.sleep(10); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
        }
        throw new AssertionError("Timed out waiting for background work");
    }
    private FilePath file(String name) { return VcsUtil.getFilePath("/changelines-regression/" + getTestName(true) + "/" + name, false); }
    private Change change(String name, String before, String after) {
        return new Change(revision(file(name), () -> before, "left"), revision(file(name), () -> after, "right"));
    }
    private static ContentRevision revision(FilePath path, Supplier<String> text, String number) {
        return new ContentRevision() {
            @Override public String getContent() { return text.get(); }
            @Override public FilePath getFile() { return path; }
            @Override public VcsRevisionNumber getRevisionNumber() {
                if (number == null) return VcsRevisionNumber.NULL;
                return new VcsRevisionNumber() {
                    @Override public String asString() { return number; }
                    @Override public int compareTo(VcsRevisionNumber other) { return number.compareTo(other.asString()); }
                };
            }
        };
    }
    private static final class NativeBrowser extends ChangesBrowserBase {
        private final DefaultTreeModel model;
        Change opened;
        NativeBrowser(Project project, List<Change> changes) {
            super(project, false, false);
            model = model(project, changes);
            init();
            getViewer().rebuildTree();
        }
        @Override protected DefaultTreeModel buildTreeModel() { return model; }
        @Override protected void onDoubleClick() {
            var selected = getViewer().getSelectionPath();
            if (selected != null) opened = (Change) ((DefaultMutableTreeNode) selected.getLastPathComponent()).getUserObject();
        }
    }
}
