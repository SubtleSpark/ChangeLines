package dev.subtlespark.changelines;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserBase;
import com.intellij.openapi.vcs.changes.ui.ChangesTree;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.event.TreeModelEvent;
import javax.swing.event.TreeModelListener;
import javax.swing.event.TreeSelectionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.event.KeyEvent;
import java.beans.PropertyChangeListener;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One comparison, owned by its actual ChangesTree, not by working-copy file paths. EDT only. */
final class ReviewSession implements Disposable {
    static final String PROPERTY = "ChangeLines.reviewSession";
    enum Status { UNREVIEWED, REVIEWED, STALE, CHECKING, UNAVAILABLE }
    record Item(Change change, TreePath path, String file) {}
    /** total excludes known non-reviewable files, but includes files still being checked. */
    record Progress(int reviewed, int total, int checking, int stale) {}

    private final WeakReference<ChangesTree> treeReference;
    private final Project project;
    private final LineStatsService statistics;
    private final ReviewStore store;
    private final String fixedScope;
    private final String temporaryScope = "temporary:" + UUID.randomUUID();
    private final Map<Change, Item> byChange = new IdentityHashMap<>();
    private final Map<Change, LineStatsService.Result> immutableResults = new IdentityHashMap<>();
    private List<Item> items = List.of();
    private List<Change> changes = List.of();
    private String scope;
    private boolean dirty = true;
    private boolean disposed;
    private TreeModel observedModel;
    private final TreeModelListener modelListener = new TreeModelListener() {
        @Override public void treeNodesChanged(TreeModelEvent e) { changed(); }
        @Override public void treeNodesInserted(TreeModelEvent e) { changed(); }
        @Override public void treeNodesRemoved(TreeModelEvent e) { changed(); }
        @Override public void treeStructureChanged(TreeModelEvent e) { changed(); }
    };
    private final PropertyChangeListener modelPropertyListener = e -> { observeModel(); changed(); };
    private final TreeSelectionListener selectionListener = e -> requestRefresh();
    private JScrollPane scrollPane;
    private Component previousHeader;
    private JPanel header;
    private ActionToolbar toolbar;
    private DefaultActionGroup hostGroup;
    private final DefaultActionGroup reviewGroup;

    ReviewSession(ChangesTree tree) { this(tree, null); }

    ReviewSession(ChangesTree tree, String fixedScope) {
        treeReference = new WeakReference<>(tree);
        project = tree.getProject();
        statistics = project.getService(LineStatsService.class);
        store = ApplicationManager.getApplication().getService(ReviewStore.class);
        this.fixedScope = fixedScope;
        scope = temporaryScope;
        reviewGroup = ReviewActions.toolbarGroup(this);
        tree.putClientProperty(PROPERTY, this);
        tree.addPropertyChangeListener("model", modelPropertyListener);
        tree.addTreeSelectionListener(selectionListener);
        observeModel();
        refresh();
    }

    Project project() { return project; }
    String scope() { syncModel(); return scope; }
    boolean isActive() { return !disposed && !project.isDisposed() && treeReference.get() != null; }
    boolean contains(Change change) { syncModel(); return isActive() && byChange.containsKey(change); }
    List<Change> changes() { syncModel(); return changes; }
    DefaultActionGroup toolbarActions() { return reviewGroup; }

    List<Change> selected() {
        syncModel();
        ChangesTree tree = treeReference.get();
        if (!isActive() || tree == null || tree.getSelectionPaths() == null) return List.of();
        List<Change> selected = new ArrayList<>();
        for (TreePath path : tree.getSelectionPaths()) {
            if (path.getLastPathComponent() instanceof DefaultMutableTreeNode node
                    && node.getUserObject() instanceof Change change && byChange.containsKey(change)) selected.add(change);
        }
        return List.copyOf(selected);
    }

    LineStatsService.Result statistics(Change change, boolean load) {
        syncModel();
        if (!isActive()) return LineStatsService.Result.of(LineStatsService.State.UNAVAILABLE);
        if (!ReviewScope.live(change)) {
            var result = immutableResults.get(change);
            if (result != null) return result;
        }
        var result = statistics.get(change, load);
        if (result.state() != LineStatsService.State.LOADING && result.state() != LineStatsService.State.UNAVAILABLE
                && !ReviewScope.live(change) && byChange.containsKey(change)) immutableResults.put(change, result);
        return result;
    }

    String suffix(Change change, LineStatsService.Result result) {
        return switch (status(change, result)) {
            case REVIEWED -> "已审阅";
            case STALE -> "需重审";
            case CHECKING -> "待核对";
            case UNAVAILABLE -> "不可审阅";
            case UNREVIEWED -> "";
        };
    }

    Status status(Change change) { syncModel(); return status(change, statistics(change, true)); }
    Status cachedStatus(Change change) { syncModel(); return status(change, statistics(change, false)); }

    private Status status(Change change, LineStatsService.Result result) {
        Item item = byChange.get(change);
        String approved = item == null ? null : store.fingerprint(scope, item.file());
        if (result.fingerprint() == null) {
            if (result.state() != LineStatsService.State.LOADING) return Status.UNAVAILABLE;
            return approved == null ? Status.UNREVIEWED : Status.CHECKING;
        }
        return approved == null ? Status.UNREVIEWED
                : approved.equals(result.fingerprint()) ? Status.REVIEWED : Status.STALE;
    }

    boolean canMark(List<Change> selected) {
        syncModel();
        if (!isActive() || selected.isEmpty()) return false;
        for (Change change : selected) {
            if (!byChange.containsKey(change) || statistics(change, false).fingerprint() == null) return false;
        }
        return true;
    }

    boolean hasMarks(List<Change> selected) {
        syncModel();
        for (Change change : selected) {
            Item item = byChange.get(change);
            if (item != null && store.fingerprint(scope, item.file()) != null) return true;
        }
        return false;
    }

    boolean mark(List<Change> selected) {
        if (!canMark(selected)) return false;
        List<String> fingerprints = new ArrayList<>();
        for (Change change : selected) {
            String fingerprint = statistics(change, false).fingerprint();
            if (fingerprint == null) return false;
            fingerprints.add(fingerprint);
        }
        for (int i = 0; i < selected.size(); i++) store.mark(scope, byChange.get(selected.get(i)).file(), fingerprints.get(i));
        refresh();
        requestRefresh();
        return true;
    }

    void unmark(List<Change> selected) {
        syncModel();
        if (!isActive()) return;
        for (Change change : selected) {
            Item item = byChange.get(change);
            if (item != null) store.unmark(scope, item.file());
        }
        refresh();
        requestRefresh();
    }

    Change nextAfter(Change current) {
        syncModel();
        if (!isActive() || items.isEmpty()) return null;
        int start = -1;
        for (int i = 0; i < items.size(); i++) if (items.get(i).change() == current) { start = i; break; }
        for (int step = 1; step <= items.size(); step++) {
            Change candidate = items.get((start + step) % items.size()).change();
            Status status = cachedStatus(candidate);
            if (status != Status.REVIEWED && status != Status.UNAVAILABLE) return candidate;
        }
        return null;
    }

    void open(Change change) {
        syncModel();
        ChangesTree tree = treeReference.get();
        Item item = byChange.get(change);
        if (!isActive() || tree == null || item == null) return;
        String openingScope = scope;
        tree.expandPath(item.path().getParentPath());
        tree.setSelectionPath(item.path());
        tree.scrollPathToVisible(item.path());
        SwingUtilities.invokeLater(() -> {
            if (!contains(change) || !openingScope.equals(scope())
                    || selected().stream().noneMatch(selected -> selected == change)) return;
            var handler = tree.getEnterKeyHandler();
            if (handler != null) handler.process(new KeyEvent(tree, KeyEvent.KEY_PRESSED,
                    System.currentTimeMillis(), 0, KeyEvent.VK_ENTER, '\n'));
        });
    }

    Progress progress() {
        syncModel();
        int reviewed = 0, checking = 0, stale = 0, skipped = 0;
        for (Item item : items) {
            var result = statistics(item.change(), false);
            Status status = status(item.change(), result);
            if (status == Status.REVIEWED) reviewed++;
            else if (status == Status.UNAVAILABLE) skipped++;
            else if (status == Status.STALE) stale++;
            if (result.state() == LineStatsService.State.LOADING) checking++;
        }
        return new Progress(reviewed, items.size() - skipped, checking, stale);
    }

    String progressText() {
        Progress p = progress();
        String text = "已审阅 " + p.reviewed() + " / " + p.total();
        int skipped = items.size() - p.total();
        if (skipped > 0) text += " · 跳过 " + skipped;
        if (p.checking() > 0) text += " · 核对中";
        return text;
    }

    String progressDescription() {
        Progress p = progress();
        return items.size() + " 个变更；" + p.stale() + " 个需重审；" + p.checking() + " 个待核对。"
                + (scope.startsWith("temporary:") ? "无法确定稳定的比较身份，仅保存当前窗口进度。"
                : "本机保存；标记仅对本次比较的左右内容有效。");
    }

    void refresh() {
        if (!isActive()) return;
        syncModel();
        if (fixedScope == null) scope = ReviewScope.resolve(treeReference.get(), changes, temporaryScope);
        installControls(treeReference.get());
        // Queue bounded background work outside renderer/action update. Every file, including
        // deleted/binary files, belongs to this comparison rather than the local working copy.
        for (Change change : changes) statistics(change, true);
        if (toolbar != null) toolbar.updateActionsAsync();
    }

    private void syncModel() {
        if (!dirty || !isActive()) return;
        dirty = false;
        ChangesTree tree = treeReference.get();
        List<Item> current = new ArrayList<>();
        byChange.clear();
        if (tree.getModel().getRoot() instanceof DefaultMutableTreeNode root) {
            var nodes = root.preorderEnumeration();
            while (nodes.hasMoreElements()) {
                if (nodes.nextElement() instanceof DefaultMutableTreeNode node
                        && node.getUserObject() instanceof Change change) {
                    var revision = change.getAfterRevision() != null ? change.getAfterRevision() : change.getBeforeRevision();
                    if (revision == null || revision.getFile().isDirectory() || byChange.containsKey(change)) continue;
                    Item item = new Item(change, new TreePath(node.getPath()), ReviewScope.file(change));
                    current.add(item);
                    byChange.put(change, item);
                }
            }
        }
        items = List.copyOf(current);
        changes = items.stream().map(Item::change).toList();
        immutableResults.keySet().removeIf(change -> !byChange.containsKey(change));
        scope = fixedScope != null ? fixedScope : ReviewScope.resolve(tree, changes, temporaryScope);
    }

    private void observeModel() {
        if (observedModel != null) observedModel.removeTreeModelListener(modelListener);
        ChangesTree tree = treeReference.get();
        observedModel = tree == null ? null : tree.getModel();
        if (observedModel != null) observedModel.addTreeModelListener(modelListener);
    }

    private void changed() { dirty = true; requestRefresh(); }
    private void requestRefresh() {
        if (!disposed) ApplicationManager.getApplication().getService(ChangeLinesInstaller.class).requestRefresh();
    }

    private void installControls(ChangesTree tree) {
        if (hostGroup != null) return;
        ChangesBrowserBase browser = (ChangesBrowserBase) SwingUtilities.getAncestorOfClass(ChangesBrowserBase.class, tree);
        if (browser != null && browser.getToolbar().getActionGroup() instanceof DefaultActionGroup nativeGroup) {
            restoreHeader();
            toolbar = browser.getToolbar();
            hostGroup = nativeGroup;
            browser.addToolbarAction(reviewGroup);
            return;
        }
        if (header != null) return;
        scrollPane = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, tree);
        if (scrollPane == null) return;
        previousHeader = scrollPane.getColumnHeader() == null ? null : scrollPane.getColumnHeader().getView();
        toolbar = ActionManager.getInstance().createActionToolbar("ChangeLines.Review", reviewGroup, true);
        toolbar.setTargetComponent(tree);
        header = new JPanel(new BorderLayout());
        header.add(toolbar.getComponent(), BorderLayout.NORTH);
        if (previousHeader != null) header.add(previousHeader, BorderLayout.CENTER);
        scrollPane.setColumnHeaderView(header);
    }

    private void restoreHeader() {
        if (scrollPane != null && scrollPane.getColumnHeader() != null && scrollPane.getColumnHeader().getView() == header) {
            scrollPane.setColumnHeaderView(previousHeader);
        }
        scrollPane = null;
        previousHeader = null;
        header = null;
    }

    @Override public void dispose() {
        if (disposed) return;
        disposed = true;
        ChangesTree tree = treeReference.get();
        if (tree != null) {
            tree.removePropertyChangeListener("model", modelPropertyListener);
            tree.removeTreeSelectionListener(selectionListener);
            if (tree.getClientProperty(PROPERTY) == this) tree.putClientProperty(PROPERTY, null);
        }
        if (observedModel != null) observedModel.removeTreeModelListener(modelListener);
        restoreHeader();
        if (hostGroup != null) hostGroup.remove(reviewGroup);
        reviewGroup.removeAll();
        hostGroup = null;
        toolbar = null;
        store.releaseTemporaryScope(temporaryScope);
        items = List.of();
        changes = List.of();
        byChange.clear();
        immutableResults.clear();
    }
}
