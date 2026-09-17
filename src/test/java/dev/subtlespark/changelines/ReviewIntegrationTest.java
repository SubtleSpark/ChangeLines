package dev.subtlespark.changelines;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.diff.util.DiffUserDataKeys;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.changes.actions.diff.ChangeDiffRequestProducer;
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserChangeNode;
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode;
import com.intellij.openapi.vcs.changes.ui.ChangesTree;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.testFramework.LightPlatformTestCase;
import com.intellij.util.ui.UIUtil;
import com.intellij.vcsUtil.VcsUtil;

import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises the installer and registered Diff extension, not only their helper methods. */
public final class ReviewIntegrationTest extends LightPlatformTestCase {
    private final List<ChangesTree> trees = new ArrayList<>();
    private ChangeLinesInstaller installer;
    private ReviewStore store;
    private ReviewStore.Data saved;

    @Override protected void setUp() throws Exception {
        super.setUp();
        installer = ApplicationManager.getApplication().getService(ChangeLinesInstaller.class);
        store = ApplicationManager.getApplication().getService(ReviewStore.class);
        saved = store.getState();
    }

    @Override protected void tearDown() throws Exception {
        try {
            trees.forEach(installer::detach);
            store.loadState(saved);
        } finally { super.tearDown(); }
    }

    public void testInstallerDecoratesTreeAndRestoresExistingHeaderOnDetach() {
        Change change = change();
        ChangesTree tree = tree(change);
        var original = tree.getCellRenderer();
        var scroll = new JScrollPane(tree);
        var existingHeader = new JLabel("Existing header");
        scroll.setColumnHeaderView(existingHeader);
        installer.attach(tree);
        assertTrue(tree.getCellRenderer() instanceof ChangeLinesRenderer);
        var session = (ReviewSession) tree.getClientProperty(ReviewSession.PROPERTY);
        assertNotNull(session);
        assertSame(session, installer.sessionFor(change));
        assertNotSame(existingHeader, scroll.getColumnHeader().getView());
        installer.attach(tree);
        assertSame(session, tree.getClientProperty(ReviewSession.PROPERTY));
        installer.detach(tree);
        assertSame(original, tree.getCellRenderer());
        assertSame(existingHeader, scroll.getColumnHeader().getView());
        assertNull(tree.getClientProperty(ReviewSession.PROPERTY));
        assertNull(installer.sessionFor(change));
    }

    public void testRegisteredExtensionAddsReviewControlsToActualNativeDiffPanel() {
        Change change = change();
        ChangesTree tree = tree(change);
        new JScrollPane(tree);
        installer.attach(tree);
        ReviewSession session = installer.sessionFor(change);
        assertNotNull(session);
        awaitReady(session, change);
        var factory = DiffContentFactory.getInstance();
        var request = new SimpleDiffRequest("Review integration", factory.create("before\n"),
                factory.create("after\n"), "before", "after");
        request.putUserData(ChangeDiffRequestProducer.CHANGE_KEY, change);
        var parent = Disposer.newDisposable("ChangeLines Diff integration");
        try {
            var panel = DiffManager.getInstance().createRequestPanel(getProject(), parent, null);
            panel.setRequest(request);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (request.getUserData(DiffUserDataKeys.CONTEXT_ACTIONS) == null && System.nanoTime() < deadline) {
                UIUtil.dispatchAllInvocationEvents();
                pause();
            }
            var actions = request.getUserData(DiffUserDataKeys.CONTEXT_ACTIONS);
            assertNotNull("Registered DiffExtension was not called by the native viewer", actions);
            assertEquals(2, actions.stream().filter(action -> action instanceof ReviewActions.BoundAction).count());
            var mark = actions.stream().filter(action -> action instanceof ReviewActions.BoundAction).findFirst().orElseThrow();
            mark.actionPerformed(AnActionEvent.createFromAnAction(mark, null, "ChangeLinesTest", DataContext.EMPTY_CONTEXT));
            assertEquals(ReviewSession.Status.REVIEWED, session.status(change));
        } finally {
            Disposer.dispose(parent);
        }
    }

    public void testAmbiguousDiffOwnerIsNotGuessed() {
        Change change = change();
        ChangesTree first = tree(change), second = tree(change);
        installer.attach(first);
        assertNotNull(installer.sessionFor(change));
        // Unknown contexts deliberately get independent temporary identities.
        installer.attach(second);
        assertNull(installer.sessionFor(change));
        installer.detach(second);
        assertNotNull(installer.sessionFor(change));
    }

    public void testReusedNativePopupReceivesReviewMenuAgain() {
        Change change = change();
        ChangesTree tree = tree(change);
        installer.attach(tree);
        var popup = new JPopupMenu();
        popup.setInvoker(tree);
        for (int i = 0; i < 2; i++) {
            popup.removeAll();
            var nativeItem = new JMenuItem("Show Diff");
            popup.add(nativeItem);
            ReviewActions.decoratePopup(popup);
            ReviewActions.decoratePopup(popup);
            assertEquals(3, popup.getComponentCount());
            assertSame(nativeItem, popup.getComponent(0));
        }
    }

    public void testDeferredNavigationAndOldDiffActionIgnoreReplacementModel() {
        Change old = change();
        ChangesTree tree = tree(old);
        installer.attach(tree);
        var session = installer.sessionFor(old);
        awaitReady(session, old);
        var action = new ReviewActions.BoundAction(session, old, ReviewActions.Kind.TOGGLE);
        AtomicInteger opened = new AtomicInteger();
        tree.setDoubleClickAndEnterKeyHandler(opened::incrementAndGet);
        session.open(old);
        Change replacement = change();
        var root = ChangesBrowserNode.createRoot();
        var node = new ChangesBrowserChangeNode(getProject(), replacement, null);
        root.add(node);
        tree.setModel(new DefaultTreeModel(root));
        tree.setSelectionPath(new TreePath(node.getPath()));
        UIUtil.dispatchAllInvocationEvents();
        assertEquals("Deferred action must not open a different model generation", 0, opened.get());
        action.actionPerformed(AnActionEvent.createFromAnAction(action, null, "ChangeLinesTest", DataContext.EMPTY_CONTEXT));
        assertEquals(ReviewSession.Status.UNREVIEWED, session.status(replacement));
    }

    private ChangesTree tree(Change change) {
        ChangesTree tree = new ChangesTree(getProject(), false, false) {
            @Override public void rebuildTree() { }
        };
        var root = ChangesBrowserNode.createRoot();
        root.add(new ChangesBrowserChangeNode(getProject(), change, null));
        tree.setModel(new DefaultTreeModel(root));
        trees.add(tree);
        return tree;
    }

    private Change change() {
        FilePath path = VcsUtil.getFilePath("/changelines-integration/" + getTestName(true) + "/Sample.txt", false);
        return new Change(revision(path, "before\n"), revision(path, "after\n"));
    }

    private static ContentRevision revision(FilePath path, String content) {
        return new ContentRevision() {
            @Override public String getContent() { return content; }
            @Override public FilePath getFile() { return path; }
            @Override public VcsRevisionNumber getRevisionNumber() { return VcsRevisionNumber.NULL; }
        };
    }

    private static void awaitReady(ReviewSession session, Change change) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (session.statistics(change, true).fingerprint() != null) return;
            UIUtil.dispatchAllInvocationEvents();
            pause();
        }
        throw new AssertionError("Content fingerprint did not finish");
    }

    private static void pause() {
        try { Thread.sleep(10); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
