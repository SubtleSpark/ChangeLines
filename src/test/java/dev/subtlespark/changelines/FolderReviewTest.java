package dev.subtlespark.changelines;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.changes.CurrentContentRevision;
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode;
import com.intellij.openapi.vcs.changes.ui.ChangesTree;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.testFramework.LightPlatformTestCase;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.util.ui.UIUtil;
import com.intellij.vcsUtil.VcsUtil;

import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Real ChangesTree rows + installed renderer + Action System. No filesystem folder substitutes. */
public final class FolderReviewTest extends LightPlatformTestCase {
    private final List<ChangesTree> attached = new ArrayList<>();
    private ReviewStore store;
    private ReviewStore.Data saved;
    private ChangeLinesInstaller installer;
    private String savedSummaryPreference;

    @Override protected void setUp() throws Exception {
        super.setUp();
        store = ApplicationManager.getApplication().getService(ReviewStore.class);
        saved = store.getState();
        installer = ApplicationManager.getApplication().getService(ChangeLinesInstaller.class);
        PropertiesComponent properties = PropertiesComponent.getInstance(getProject());
        savedSummaryPreference = properties.getValue(FolderSummarySettings.KEY);
        properties.unsetValue(FolderSummarySettings.KEY);
    }

    @Override protected void tearDown() throws Exception {
        try {
            attached.forEach(installer::detach);
            store.loadState(saved);
            PropertiesComponent.getInstance(getProject()).setValue(FolderSummarySettings.KEY, savedSummaryPreference);
        } finally { super.tearDown(); }
    }

    public void testNestedCollapsedFoldersSumAddsDeletesAndPreserveNativeRows() {
        FolderSummarySettings.setVisible(getProject(), true);
        Fixture f = normal();
        ready(f);
        f.tree().collapsePath(path(f.main()));
        FolderSummary main = f.session().folderSummary(f.main());
        assertEquals(3L, main.added());
        assertEquals(3L, main.removed());
        assertEquals(3, main.files());
        assertEquals(3, main.counted());
        assertEquals(1L, f.session().folderSummary(f.nested()).added());
        assertEquals(3L, f.session().folderSummary(f.nested()).removed());
        assertEquals(4L, f.session().folderSummary(f.root()).added());
        assertEquals(3L, f.session().folderSummary(f.root()).removed());
        assertTrue(f.tree().getCellRenderer() instanceof ChangeLinesRenderer);
        var wrapper = (ChangeLinesRenderer) f.tree().getCellRenderer();
        Component original = wrapper.delegate.getTreeCellRendererComponent(f.tree(), f.main(), false, false, false, 0, false);
        for (int i = 0; i < 3; i++) {
            Component decorated = wrapper.getTreeCellRendererComponent(f.tree(), f.main(), false, false, false, 0, false);
            assertSame(original, decorated);
            String text = label(decorated).getCharSequence(false).toString();
            assertTrue(text, text.contains("main"));
            assertTrue(text, text.contains("  +3  -3"));
            assertTrue(text, text.contains("已审阅 0 / 3"));
            assertEquals(text.indexOf("  +3"), text.lastIndexOf("  +3"));
        }
    }

    public void testPrimaryToolbarKeepsOnlyProgressMarkAndReviewMenu() {
        Fixture f = normal();
        AnAction[] actions = f.session().toolbarActions().getChildren(null);
        assertEquals(3, actions.length);
        assertEquals("标记已审阅", actions[1].getTemplatePresentation().getText());
        assertTrue(actions[2] instanceof ActionGroup);
        assertEquals("审阅", actions[2].getTemplatePresentation().getText());
        for (AnAction action : actions) {
            String text = action.getTemplatePresentation().getText();
            assertFalse("审阅并下一个".equals(text));
            assertFalse("下一个未审阅".equals(text));
        }
    }

    public void testFolderToolbarMarksAllDescendantsAndToggleCancelsWithoutTouchingSibling() {
        Fixture f = normal();
        ready(f);
        f.tree().collapsePath(path(f.main()));
        select(f, f.main());
        assertEquals(f.inside(), f.session().selected());
        assertTrue(f.session().foldersSelected());
        AnAction toggle = toolbar(f, 1);
        assertEnabled(toggle, true);
        toggle.actionPerformed(event(toggle));
        for (Change change : f.inside()) assertEquals(ReviewSession.Status.REVIEWED, f.session().status(change));
        assertEquals(ReviewSession.Status.UNREVIEWED, f.session().status(f.outside()));
        assertEquals(3, f.session().folderSummary(f.main()).reviewed());
        assertEquals("已审阅", f.session().folderSummary(f.main()).suffix());
        assertEquals(3, f.session().progress().reviewed());
        var updated = event(toggle);
        toggle.update(updated);
        assertEquals("取消已审阅", updated.getPresentation().getText());
        toggle.actionPerformed(event(toggle));
        for (Change change : f.inside()) assertEquals(ReviewSession.Status.UNREVIEWED, f.session().status(change));
        assertEquals(0, f.session().folderSummary(f.main()).reviewed());
    }

    public void testOverlappingFolderAndFileSelectionsAreDeduplicatedInTreeOrder() {
        Fixture f = normal();
        ready(f);
        f.tree().setSelectionPaths(new TreePath[]{path(node(f, f.inside().getLast())), path(f.nested()), path(f.main())});
        assertEquals(f.inside(), f.session().selected());
        assertEquals(3, f.session().selectedForReview().size());
        ReviewActions.performSelected(f.session(), ReviewActions.Kind.MARK);
        assertEquals(3, f.session().progress().reviewed());
        assertEquals(ReviewSession.Status.UNREVIEWED, f.session().status(f.outside()));
        assertEquals(3, f.session().folderSummary(f.main()).files());
    }

    public void testSubfolderBatchLeavesParentFileUnreviewedAndCanReviewThenNext() {
        Fixture f = normal();
        ready(f);
        select(f, f.nested());
        assertEquals(f.inside().subList(1, 3), f.session().selected());
        AtomicInteger opened = new AtomicInteger();
        f.tree().setDoubleClickAndEnterKeyHandler(opened::incrementAndGet);
        ReviewActions.performSelected(f.session(), ReviewActions.Kind.MARK_NEXT);
        UIUtil.dispatchAllInvocationEvents();
        assertEquals(List.of(f.outside()), f.session().selected());
        assertEquals(1, opened.get());
        assertEquals(ReviewSession.Status.UNREVIEWED, f.session().status(f.inside().getFirst()));
        assertEquals(2, f.session().folderSummary(f.main()).reviewed());
    }

    public void testMixedFolderSkipsUnsupportedButDirectMultiFileMarkRemainsAtomic() {
        Change a = change("main/A.txt", "old\n", "new\n");
        Change binary = change("main/nested/Binary.txt", "", "a\0b");
        Change missing = change("main/nested/Missing.txt", null, "new\n");
        Fixture f = fixture(a, binary, missing);
        ready(f);
        select(f, f.main());
        assertEquals(List.of(a), f.session().selectedForReview());
        AnAction toggle = toolbar(f, 1);
        assertEnabled(toggle, true);
        toggle.actionPerformed(event(toggle));
        assertEquals(ReviewSession.Status.REVIEWED, f.session().status(a));
        assertEquals(ReviewSession.Status.UNAVAILABLE, f.session().status(binary));
        assertNull(store.fingerprint(f.session().scope(), ReviewScope.file(binary)));
        assertNull(store.fingerprint(f.session().scope(), ReviewScope.file(missing)));
        FolderSummary summary = f.session().folderSummary(f.main());
        assertEquals(1, summary.reviewed());
        assertEquals(2, summary.skipped());
        assertEquals(1, summary.reviewable());
        assertTrue(summary.suffix(), summary.suffix().contains("部分统计 1 / 3"));
        assertTrue(summary.suffix(), summary.suffix().contains("跳过 2"));
        toggle.actionPerformed(event(toggle));
        assertEquals(ReviewSession.Status.UNREVIEWED, f.session().status(a));
        f.tree().setSelectionPaths(new TreePath[]{path(node(f, a)), path(node(f, missing))});
        assertFalse(f.session().foldersSelected());
        assertEquals(List.of(a, missing), f.session().selectedForReview());
        assertFalse(f.session().mark(f.session().selectedForReview()));
        assertEquals(ReviewSession.Status.UNREVIEWED, f.session().status(a));
    }

    public void testEntirelyUnsupportedFolderDoesNotDisplayZeroStatsOrReviewed() {
        FolderSummarySettings.setVisible(getProject(), true);
        Fixture f = fixture(change("main/A.txt", "a", "\0"),
                change("main/nested/B.txt", null, "b"), change("main/nested/C.txt", "\0", "a"));
        ready(f);
        select(f, f.main());
        assertEnabled(toolbar(f, 1), false);
        FolderSummary summary = f.session().folderSummary(f.main());
        assertEquals(0, summary.counted());
        assertEquals(0, summary.reviewable());
        assertTrue(summary.suffix().contains("统计不可用"));
        assertFalse(summary.suffix().contains("已审阅"));
        String text = render(f, f.main());
        assertFalse(text, text.contains("  +0"));
        assertTrue(text, text.contains("跳过 3"));
    }

    public void testPendingChildIsNotSkippedAndPreventsPartialBatchApproval() {
        FolderSummarySettings.setVisible(getProject(), true);
        CountDownLatch release = new CountDownLatch(1);
        Change delayed = new Change(null, revision(file("main/nested/Delayed.txt"), () -> {
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test latch timed out");
                return "new\n";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException();
            }
        }, "right"));
        Fixture f = fixture(change("main/A.txt", "a\n", "b\n"), delayed,
                change("main/nested/C.txt", "x\n", "y\n"));
        try {
            waitFor(() -> f.session().statistics(f.inside().getFirst(), true).fingerprint() != null);
            f.session().refresh();
            select(f, f.main());
            assertEquals(3, f.session().selectedForReview().size());
            assertEnabled(toolbar(f, 1), false);
            assertFalse(f.session().mark(f.session().selectedForReview()));
            assertEquals(0, f.session().progress().reviewed());
            assertTrue(f.session().folderSummary(f.main()).checking() > 0);
            assertTrue(render(f, f.main()).contains("统计中"));
        } finally { release.countDown(); }
        ready(f);
        assertEnabled(toolbar(f, 1), true);
        toolbar(f, 1).actionPerformed(event(toolbar(f, 1)));
        assertEquals(3, f.session().folderSummary(f.main()).reviewed());
    }

    public void testEditingOneReviewedChildInvalidatesAncestorAndRecomputesTotals() {
        AtomicReference<String> text = new AtomicReference<>("b\n");
        FilePath path = file("main/nested/Live.txt");
        Change live = new Change(revision(path, () -> "a\n", "left"), new CurrentContentRevision(path) {
            @Override public String getContent() { return text.get(); }
        });
        Fixture f = fixture(change("main/A.txt", "a\n", "b\n"), live,
                change("main/nested/C.txt", "a\n", "b\n"));
        ready(f);
        select(f, f.main());
        ReviewActions.performSelected(f.session(), ReviewActions.Kind.MARK);
        assertEquals("已审阅", f.session().folderSummary(f.main()).suffix());
        text.set("b\nextra\n");
        getProject().getService(LineStatsService.class).invalidateWorkingTree();
        f.session().refresh();
        assertTrue(f.session().folderSummary(f.main()).reviewed() < 3);
        ready(f);
        FolderSummary summary = f.session().folderSummary(f.main());
        assertEquals(4L, summary.added());
        assertEquals(3L, summary.removed());
        assertEquals(2, summary.reviewed());
        assertEquals(1, summary.stale());
        assertTrue(summary.suffix().contains("需重审 1"));
        assertFalse(render(f, f.main()).contains("需重审"));
        assertTrue(render(f, node(f, live)).contains("需重审"));
        FolderSummarySettings.setVisible(getProject(), true);
        assertTrue(render(f, f.main()).contains("  +4  -3"));
        assertTrue(render(f, f.main()).contains("需重审 1"));
        ReviewActions.performSelected(f.session(), ReviewActions.Kind.MARK);
        assertEquals("已审阅", f.session().folderSummary(f.main()).suffix());
    }

    public void testNewDescendantUpdatesCountWithoutInheritingFolderApproval() {
        Fixture f = normal();
        ready(f);
        select(f, f.main());
        ReviewActions.performSelected(f.session(), ReviewActions.Kind.MARK);
        Change extra = change("main/nested/New.txt", "old\n", "new\nextra\n");
        ((DefaultTreeModel) f.tree().getModel()).insertNodeInto(
                ChangesBrowserNode.createChange(getProject(), extra), f.nested(), f.nested().getChildCount());
        assertNull(f.session().folderSummary(f.main()));
        waitFor(() -> f.session().statistics(extra, true).fingerprint() != null);
        f.session().refresh();
        assertEquals(4, f.session().folderSummary(f.main()).files());
        assertEquals(5L, f.session().folderSummary(f.main()).added());
        assertEquals(3, f.session().folderSummary(f.main()).reviewed());
        assertEquals(ReviewSession.Status.UNREVIEWED, f.session().status(extra));
        assertEquals(f.inside().size() + 1, f.session().selected().size());
    }

    public void testPaintingAndToolbarUpdatesUseCachedSummariesWithoutRevisionIo() {
        FolderSummarySettings.setVisible(getProject(), true);
        AtomicInteger reads = new AtomicInteger();
        AtomicBoolean readOnEdt = new AtomicBoolean();
        Supplier<String> content = () -> {
            reads.incrementAndGet();
            if (SwingUtilities.isEventDispatchThread()) readOnEdt.set(true);
            return "line\n";
        };
        Change a = new Change(null, revision(file("main/A.txt"), content, "right"));
        Fixture f = fixture(a, change("main/nested/B.txt", "a", "b"), change("main/nested/C.txt", "a", "b"));
        ready(f);
        select(f, f.main());
        int previousReads = reads.get();
        for (int i = 0; i < 100; i++) {
            render(f, f.main());
            assertEnabled(toolbar(f, 1), true);
        }
        assertEquals(previousReads, reads.get());
        assertFalse("Revision content must not be read on the EDT", readOnEdt.get());
    }

    public void testDiffMarkStaysSingleFileWhenTreeHasSelectedFolder() {
        Fixture f = normal();
        ready(f);
        select(f, f.main());
        AnAction diffMark = new ReviewActions.BoundAction(f.session(), f.inside().get(1), ReviewActions.Kind.TOGGLE);
        diffMark.actionPerformed(event(diffMark));
        assertEquals(1, f.session().folderSummary(f.main()).reviewed());
        assertEquals(ReviewSession.Status.REVIEWED, f.session().status(f.inside().get(1)));
        assertEquals(ReviewSession.Status.UNREVIEWED, f.session().status(f.inside().get(0)));
        assertEquals(ReviewSession.Status.UNREVIEWED, f.session().status(f.inside().get(2)));
    }

    public void testFolderSumUsesLongInsteadOfOverflowingInt() {
        FolderSummary.Builder builder = new FolderSummary.Builder();
        var result = new LineStatsService.Result(LineStatsService.State.READY,
                new LineDiff.Stats(Integer.MAX_VALUE, Integer.MAX_VALUE), ReviewFingerprint.content("a", "b"));
        builder.add(result, ReviewSession.Status.UNREVIEWED);
        builder.add(result, ReviewSession.Status.UNREVIEWED);
        assertEquals(2L * Integer.MAX_VALUE, builder.build().added());
        assertEquals(2L * Integer.MAX_VALUE, builder.build().removed());
    }

    public void testSummaryIsHiddenByDefaultButFilesAndFolderBatchReviewStillWork() {
        Fixture f = normal();
        ready(f);
        assertFalse(FolderSummarySettings.isVisible(getProject()));
        assertEquals(nativeText(f, f.main()), render(f, f.main()));
        assertEquals(nativeText(f, f.nested()), render(f, f.nested()));
        assertEquals(nativeText(f, f.root()), render(f, f.root()));
        assertTrue(render(f, node(f, f.inside().getFirst())).contains("  +2  -0"));
        select(f, f.main());
        AnAction review = toolbar(f, 1);
        assertEnabled(review, true);
        review.actionPerformed(event(review));
        assertEquals(3, f.session().progress().reviewed());
        assertTrue(render(f, node(f, f.inside().getFirst())).contains("已审阅"));
        assertEquals(nativeText(f, f.main()), render(f, f.main()));
        assertEquals(ReviewSession.Status.UNREVIEWED, f.session().status(f.outside()));
        review.actionPerformed(event(review));
        assertEquals(0, f.session().progress().reviewed());
    }

    public void testNativeMenuToggleChangesOnlyFolderPresentation() {
        Fixture f = normal();
        ready(f);
        select(f, f.main());
        ToggleAction toggle = summaryToggle(f.session());
        assertEnabled(toggle, true);
        assertFalse(toggle.isSelected(event(toggle)));
        var model = f.tree().getModel();
        var selection = f.tree().getSelectionPath();
        var marks = store.getState().reviewed;
        String fileText = render(f, node(f, f.inside().getFirst()));
        for (int i = 0; i < 3; i++) {
            toggle.actionPerformed(event(toggle));
            assertTrue(toggle.isSelected(event(toggle)));
            assertEquals("true", PropertiesComponent.getInstance(getProject()).getValue(FolderSummarySettings.KEY));
            String text = render(f, f.main());
            assertTrue(text, text.contains("  +3  -3"));
            assertTrue(text, text.contains("已审阅 0 / 3"));
            assertEquals(text.indexOf("  +3"), text.lastIndexOf("  +3"));
            toggle.actionPerformed(event(toggle));
            assertFalse(toggle.isSelected(event(toggle)));
            assertFalse(PropertiesComponent.getInstance(getProject()).isValueSet(FolderSummarySettings.KEY));
            assertEquals(nativeText(f, f.main()), render(f, f.main()));
            assertEquals(fileText, render(f, node(f, f.inside().getFirst())));
        }
        assertSame(model, f.tree().getModel());
        assertEquals(selection, f.tree().getSelectionPath());
        assertEquals(marks, store.getState().reviewed);
        assertEquals(0, f.session().progress().reviewed());
    }

    public void testSummaryPreferenceIsSharedAndSurvivesRecreatingComparison() {
        Fixture first = normal();
        Fixture second = normal();
        ready(first);
        ready(second);
        ToggleAction one = summaryToggle(first.session());
        ToggleAction two = summaryToggle(second.session());
        one.actionPerformed(event(one));
        assertTrue(two.isSelected(event(two)));
        assertTrue(render(second, second.main()).contains("  +3  -3"));
        installer.detach(first.tree());
        installer.attach(first.tree());
        ReviewSession reopened = (ReviewSession) first.tree().getClientProperty(ReviewSession.PROPERTY);
        assertNotSame(first.session(), reopened);
        assertTrue(summaryToggle(reopened).isSelected(event(one)));
        two.actionPerformed(event(two));
        assertFalse(summaryToggle(reopened).isSelected(event(one)));
    }

    public void testSummaryToggleIsAvailableWithoutSelectingFiles() {
        Fixture f = normal();
        f.tree().clearSelection();
        ToggleAction toggle = summaryToggle(f.session());
        assertEnabled(toolbar(f, 1), false);
        assertEnabled(toggle, true);
        toggle.actionPerformed(event(toggle));
        assertTrue(FolderSummarySettings.isVisible(getProject()));
        assertEquals(0, f.session().progress().reviewed());
    }

    public void testClosedComparisonToggleCannotChangePreference() {
        Fixture f = normal();
        ToggleAction toggle = summaryToggle(f.session());
        installer.detach(f.tree());
        assertEnabled(toggle, false);
        toggle.setSelected(event(toggle), true);
        assertFalse(FolderSummarySettings.isVisible(getProject()));
    }

    private static ToggleAction summaryToggle(ReviewSession session) {
        for (AnAction action : session.toolbarActions().getChildren(null)) {
            if (action instanceof ActionGroup group) {
                for (AnAction child : group.getChildren(null)) {
                    if (child instanceof ToggleAction toggle
                            && "显示文件夹汇总".equals(child.getTemplatePresentation().getText())) return toggle;
                }
            }
        }
        throw new AssertionError("Folder summary toggle is missing from the Review menu");
    }

    private static String nativeText(Fixture f, DefaultMutableTreeNode node) {
        var wrapper = (ChangeLinesRenderer) f.tree().getCellRenderer();
        Component component = wrapper.delegate.getTreeCellRendererComponent(f.tree(), node, false, false, false, 0, false);
        return label(component).getCharSequence(false).toString();
    }

    private Fixture normal() {
        return fixture(new Change(null, revision(file("main/A.txt"), () -> "a\nb\n", "right")),
                change("main/nested/B.txt", "old\n", "new\n"),
                new Change(revision(file("main/nested/C.txt"), () -> "a\nb\n", "left"), null));
    }

    private Fixture fixture(Change a, Change b, Change c) {
        ChangesTree tree = new ChangesTree(getProject(), false, false) {
            @Override public void rebuildTree() { }
        };
        var root = ChangesBrowserNode.createRoot();
        var main = ChangesBrowserNode.createFilePath(VcsUtil.getFilePath(file("main").getPath(), true));
        // A compact package/directory row: descendants are determined by the tree, not text prefixes.
        var nested = ChangesBrowserNode.createFilePath(VcsUtil.getFilePath(file("main/java/com/example").getPath(), true));
        Change outside = new Change(null, revision(file("Other.txt"), () -> "outside\n", "right"));
        root.add(main);
        main.add(ChangesBrowserNode.createChange(getProject(), a));
        main.add(nested);
        nested.add(ChangesBrowserNode.createChange(getProject(), b));
        nested.add(ChangesBrowserNode.createChange(getProject(), c));
        root.add(ChangesBrowserNode.createChange(getProject(), outside));
        tree.setModel(new DefaultTreeModel(root));
        new JScrollPane(tree);
        installer.attach(tree);
        attached.add(tree);
        ReviewSession session = (ReviewSession) tree.getClientProperty(ReviewSession.PROPERTY);
        assertNotNull(session);
        return new Fixture(tree, session, root, main, nested, List.of(a, b, c), outside);
    }

    private static void ready(Fixture f) {
        waitFor(() -> f.session().changes().stream()
                .allMatch(c -> f.session().statistics(c, true).state() != LineStatsService.State.LOADING));
        f.session().refresh();
    }
    private static void select(Fixture f, DefaultMutableTreeNode node) { f.tree().setSelectionPath(path(node)); }
    private static TreePath path(DefaultMutableTreeNode node) { return new TreePath(node.getPath()); }
    private static AnAction toolbar(Fixture f, int index) { return f.session().toolbarActions().getChildren(null)[index]; }
    private static AnActionEvent event(AnAction action) {
        return AnActionEvent.createFromAnAction(action, null, "ChangeLinesFolderTest", DataContext.EMPTY_CONTEXT);
    }
    private static void assertEnabled(AnAction action, boolean enabled) {
        var event = event(action);
        action.update(event);
        assertEquals(enabled, event.getPresentation().isEnabled());
    }
    private static DefaultMutableTreeNode node(Fixture f, Change change) {
        var nodes = f.root().preorderEnumeration();
        while (nodes.hasMoreElements()) {
            var node = (DefaultMutableTreeNode) nodes.nextElement();
            if (node.getUserObject() == change) return node;
        }
        throw new AssertionError("Missing change node");
    }
    private static String render(Fixture f, DefaultMutableTreeNode node) {
        Component component = f.tree().getCellRenderer().getTreeCellRendererComponent(f.tree(), node, false, false, false, 0, false);
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
    private static void waitFor(BooleanSupplier done) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (done.getAsBoolean()) return;
            UIUtil.dispatchAllInvocationEvents();
            try { Thread.sleep(10); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
        }
        throw new AssertionError("Folder statistics did not finish");
    }
    private FilePath file(String name) { return VcsUtil.getFilePath("/changelines-folders/" + getTestName(true) + "/" + name, false); }
    private Change change(String name, String before, String after) {
        return new Change(revision(file(name), () -> before, "left"), revision(file(name), () -> after, "right"));
    }
    private static ContentRevision revision(FilePath file, Supplier<String> content, String revision) {
        return new ContentRevision() {
            @Override public String getContent() { return content.get(); }
            @Override public FilePath getFile() { return file; }
            @Override public VcsRevisionNumber getRevisionNumber() {
                return new VcsRevisionNumber() {
                    @Override public String asString() { return revision; }
                    @Override public int compareTo(VcsRevisionNumber other) { return revision.compareTo(other.asString()); }
                };
            }
        };
    }
    private record Fixture(ChangesTree tree, ReviewSession session, ChangesBrowserNode<?> root,
                           ChangesBrowserNode<?> main, ChangesBrowserNode<?> nested, List<Change> inside, Change outside) { }
}
