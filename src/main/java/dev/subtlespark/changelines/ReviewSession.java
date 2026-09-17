package dev.subtlespark.changelines;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ui.ChangesTree;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;

import javax.swing.JButton;
import javax.swing.JComponent;
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

/** EDT-owned review context for one native tree. Approvals never follow tree selection in a Diff tab. */
final class ReviewSession implements Disposable {
    static final String PROPERTY = "ChangeLines.reviewSession";
    enum Status { UNREVIEWED, REVIEWED, STALE, CHECKING, UNAVAILABLE }
    record Item(Change change, TreePath path, String file) {}
    record Progress(int reviewed, int total, int checking, int stale) {}

    private final WeakReference<ChangesTree> treeReference;
    private final Project project;
    private final LineStatsService statistics;
    private final ReviewStore store;
    private final String fixedScope;
    private final String temporaryScope = "temporary:" + UUID.randomUUID();
    private final Map<Change, Item> byChange = new IdentityHashMap<>();
    // Keep only tiny immutable results while this tree exists. This avoids repeatedly
    // evicting/reloading fingerprints when a review has more than 2,048 files.
    private final Map<Change, LineStatsService.Result> immutableResults = new IdentityHashMap<>();
    private List<Item> items = List.of();
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
    private final JBLabel progressLabel = new JBLabel();

    ReviewSession(ChangesTree tree) { this(tree, null); }

    ReviewSession(ChangesTree tree, String fixedScope) {
        this.treeReference = new WeakReference<>(tree);
        this.project = tree.getProject();
        this.statistics = project.getService(LineStatsService.class);
        this.store = ApplicationManager.getApplication().getService(ReviewStore.class);
        this.fixedScope = fixedScope;
        scope = temporaryScope;
        tree.putClientProperty(PROPERTY, this);
        tree.addPropertyChangeListener("model", modelPropertyListener);
        tree.addTreeSelectionListener(selectionListener);
        observeModel();
        installHeader(tree);
        refresh();
    }

    Project project() { return project; }
    String scope() { syncModel(); return scope; }
    boolean isActive() { return !disposed && !project.isDisposed() && treeReference.get() != null; }
    boolean contains(Change change) { syncModel(); return isActive() && byChange.containsKey(change); }

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
        if (!isActive()) return LineStatsService.Result.of(LineStatsService.State.UNAVAILABLE);
        if (!ReviewScope.live(change)) {
            var result = immutableResults.get(change);
            if (result != null) return result;
        }
        var result = statistics.get(change, load);
        if (result.fingerprint() != null && !ReviewScope.live(change) && byChange.containsKey(change)) {
            immutableResults.put(change, result);
        }
        return result;
    }

    String suffix(Change change, LineStatsService.Result result) {
        return switch (status(change, result)) {
            case REVIEWED -> "已审阅";
            case STALE -> "需重审";
            case CHECKING -> "待核对";
            case UNAVAILABLE -> "无法核对";
            case UNREVIEWED -> "";
        };
    }

    Status status(Change change) { syncModel(); return status(change, statistics(change, true)); }

    private Status status(Change change, LineStatsService.Result result) {
        Item item = byChange.get(change);
        String approved = item == null ? null : store.fingerprint(scope, item.file());
        if (approved == null) return Status.UNREVIEWED;
        if (result.fingerprint() == null) {
            return result.state() == LineStatsService.State.LOADING ? Status.CHECKING : Status.UNAVAILABLE;
        }
        return approved.equals(result.fingerprint()) ? Status.REVIEWED : Status.STALE;
    }

    boolean canMark(List<Change> changes) {
        syncModel();
        if (!isActive() || changes.isEmpty()) return false;
        boolean ready = true;
        for (Change change : changes) {
            ready &= byChange.containsKey(change) && statistics(change, true).fingerprint() != null;
        }
        return ready;
    }

    boolean hasMarks(List<Change> changes) {
        syncModel();
        for (Change change : changes) {
            Item item = byChange.get(change);
            if (item != null && store.fingerprint(scope, item.file()) != null) return true;
        }
        return false;
    }

    boolean mark(List<Change> changes) {
        if (!canMark(changes)) return false;
        // Snapshot every selection before writing any approval. Never partially apply
        // a multi-selection if a document was invalidated while the action was queued.
        List<String> fingerprints = new ArrayList<>();
        for (Change change : changes) {
            String fingerprint = statistics(change, false).fingerprint();
            if (fingerprint == null) return false;
            fingerprints.add(fingerprint);
        }
        for (int i = 0; i < changes.size(); i++) store.mark(scope, byChange.get(changes.get(i)).file(), fingerprints.get(i));
        refresh();
        requestRefresh();
        return true;
    }

    void unmark(List<Change> changes) {
        syncModel();
        if (!isActive()) return;
        for (Change change : changes) {
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
            // Loading or unavailable records are not treated as reviewed.
            if (status(candidate) != Status.REVIEWED) return candidate;
        }
        return null;
    }

    void open(Change change) {
        syncModel();
        ChangesTree tree = treeReference.get();
        Item item = byChange.get(change);
        if (!isActive() || tree == null || item == null) return;
        tree.expandPath(item.path().getParentPath());
        tree.setSelectionPath(item.path());
        tree.scrollPathToVisible(item.path());
        // Reuse the tree's native Enter callback, preserving its Diff preview behavior.
        SwingUtilities.invokeLater(() -> {
            if (!isActive() || !selected().contains(change)) return;
            var handler = tree.getEnterKeyHandler();
            if (handler != null) handler.process(new KeyEvent(tree, KeyEvent.KEY_PRESSED,
                    System.currentTimeMillis(), 0, KeyEvent.VK_ENTER, '\n'));
        });
    }

    Progress progress() {
        syncModel();
        int reviewed = 0, checking = 0, stale = 0;
        for (Item item : items) {
            if (store.fingerprint(scope, item.file()) == null) continue;
            Status status = status(item.change(), statistics(item.change(), true));
            if (status == Status.REVIEWED) reviewed++;
            else if (status == Status.CHECKING || status == Status.UNAVAILABLE) checking++;
            else if (status == Status.STALE) stale++;
        }
        return new Progress(reviewed, items.size(), checking, stale);
    }

    void refresh() {
        if (!isActive()) return;
        Progress progress = progress();
        for (Change change : selected()) statistics(change, true);
        String text = "已审阅 " + progress.reviewed() + " / " + progress.total();
        if (progress.checking() > 0) text += " · 待核对 " + progress.checking();
        if (progress.stale() > 0) text += " · 需重审 " + progress.stale();
        progressLabel.setText(text);
        progressLabel.setToolTipText(scope.startsWith("temporary:")
                ? "无法确定此比较的稳定身份；本窗口使用临时审阅记录。"
                : "本机保存进度；标记只对当前文件两侧内容有效。右键文件或点击审阅菜单进行操作。" );
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
        immutableResults.keySet().removeIf(change -> !byChange.containsKey(change));
        scope = fixedScope != null ? fixedScope : ReviewScope.resolve(tree,
                items.stream().map(Item::change).toList(), temporaryScope);
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

    private void installHeader(ChangesTree tree) {
        scrollPane = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, tree);
        if (scrollPane == null) return;
        previousHeader = scrollPane.getColumnHeader() == null ? null : scrollPane.getColumnHeader().getView();
        header = new JPanel(new BorderLayout());
        JPanel controls = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        controls.setBorder(JBUI.Borders.empty(3, 6));
        controls.add(progressLabel, BorderLayout.CENTER);
        JButton menu = new JButton("审阅");
        menu.addActionListener(e -> ReviewActions.menu(this).show(menu, 0, menu.getHeight()));
        controls.add(menu, BorderLayout.EAST);
        header.add(controls, BorderLayout.NORTH);
        if (previousHeader != null) header.add(previousHeader, BorderLayout.CENTER);
        scrollPane.setColumnHeaderView(header);
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
        if (scrollPane != null && scrollPane.getColumnHeader() != null && scrollPane.getColumnHeader().getView() == header) {
            scrollPane.setColumnHeaderView(previousHeader);
        }
        items = List.of();
        byChange.clear();
        immutableResults.clear();
    }
}
