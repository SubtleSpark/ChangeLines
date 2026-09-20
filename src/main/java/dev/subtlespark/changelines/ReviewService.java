package dev.subtlespark.changelines;

import com.intellij.ide.projectView.ProjectView;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeList;
import com.intellij.openapi.vcs.changes.ChangeListListener;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.changes.actions.diff.ShowDiffAction;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service(Service.Level.PROJECT)
public final class ReviewService implements Disposable {
    private static final int MAX_TEXT_CHARS = 2_000_000;
    private static final long MAX_UNVERSIONED_BYTES = 4_000_000L;

    public record Decoration(@Nullable LineDiff.Stats stats,
                             ReviewStatus status,
                             String detail,
                             boolean loading) {}

    public record Progress(int changed,
                           int reviewable,
                           int reviewed,
                           int needsReview,
                           int skipped,
                           int loading) {
        public String shortText() {
            int denominator = reviewable + loading;
            return "审阅 " + reviewed + " / " + denominator + (loading > 0 ? "…" : "");
        }

        public String description() {
            return changed + " 个变更；" + reviewable + " 个可审阅；"
                    + reviewed + " 个已审阅；" + needsReview + " 个需重审；"
                    + skipped + " 个跳过";
        }
    }

    private static final class Entry {
        final String path;
        final String storeKey;
        final String vcsRoot;
        final String beforeRevision;
        final String signature;
        final @Nullable VirtualFile file;
        final @Nullable Change change;
        final boolean unversioned;
        final boolean deleted;
        volatile @Nullable Computed computed;

        Entry(String path,
              String storeKey,
              String vcsRoot,
              String beforeRevision,
              String signature,
              @Nullable VirtualFile file,
              @Nullable Change change,
              boolean unversioned,
              boolean deleted) {
            this.path = path;
            this.storeKey = storeKey;
            this.vcsRoot = vcsRoot;
            this.beforeRevision = beforeRevision;
            this.signature = signature;
            this.file = file;
            this.change = change;
            this.unversioned = unversioned;
            this.deleted = deleted;
        }
    }

    private record Computed(@Nullable String fingerprint,
                            @Nullable LineDiff.Stats stats,
                            ReviewStatus status,
                            String detail) {}

    private final Project project;
    private final ReviewStore store;
    private final ChangeListManager changeListManager;
    private final ExecutorService workers;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean refreshScheduled = new AtomicBoolean();
    private final AtomicBoolean uiRefreshScheduled = new AtomicBoolean();
    private volatile Map<String, Entry> entries = Map.of();
    private volatile boolean disposed;

    public ReviewService(Project project) {
        this.project = project;
        this.store = project.getService(ReviewStore.class);
        this.changeListManager = ChangeListManager.getInstance(project);
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory threads = task -> {
            Thread thread = new Thread(task, "ChangeLines-Review-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.workers = Executors.newFixedThreadPool(2, threads);

        project.getMessageBus().connect(this).subscribe(ChangeListListener.TOPIC, new ChangeListListener() {
            @Override public void changeListUpdateDone() { scheduleRefresh(); }
            @Override public void changesAdded(Collection<? extends Change> changes, ChangeList toList) { scheduleRefresh(); }
            @Override public void changesRemoved(Collection<? extends Change> changes, ChangeList fromList) { scheduleRefresh(); }
            @Override public void changedFileStatusChanged(boolean upToDate) { if (upToDate) scheduleRefresh(); }
            @Override public void unchangedFileStatusChanged(boolean upToDate) { if (upToDate) scheduleRefresh(); }
        });

        EditorFactory.getInstance().getEventMulticaster().addDocumentListener(new DocumentListener() {
            @Override
            public void documentChanged(@NotNull DocumentEvent event) {
                VirtualFile file = FileDocumentManager.getInstance().getFile(event.getDocument());
                if (file != null) {
                    invalidate(file.getPath());
                    scheduleRefresh();
                }
            }
        }, this);

        project.getMessageBus().connect(this).subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
            @Override
            public void after(@NotNull List<? extends VFileEvent> events) {
                boolean relevant = false;
                Map<String, Entry> current = entries;
                for (VFileEvent event : events) {
                    String path = normalize(event.getPath());
                    Entry entry = current.get(path);
                    if (entry != null) {
                        invalidate(path);
                        relevant = true;
                    }
                }
                if (relevant) scheduleRefresh();
            }
        });
    }

    public void start() {
        if (started.compareAndSet(false, true)) scheduleRefresh();
    }

    public @Nullable Decoration decoration(@NotNull VirtualFile file) {
        Entry entry = entries.get(normalize(file.getPath()));
        if (entry == null) return null;
        Computed computed = entry.computed;
        if (computed == null) {
            return new Decoration(null, ReviewStatus.UNREVIEWED, "正在计算变更信息", true);
        }
        return new Decoration(computed.stats(), computed.status(), computed.detail(), false);
    }

    public boolean isTracked(@NotNull VirtualFile file) {
        return entries.containsKey(normalize(file.getPath()));
    }

    public boolean canMark(@NotNull Collection<VirtualFile> files) {
        for (VirtualFile file : files) {
            Entry entry = entries.get(normalize(file.getPath()));
            if (entry != null && reviewable(entry.computed)) return true;
        }
        return false;
    }

    public boolean canUnmark(@NotNull Collection<VirtualFile> files) {
        for (VirtualFile file : files) {
            Entry entry = entries.get(normalize(file.getPath()));
            if (entry != null && store.contains(entry.storeKey)) return true;
        }
        return false;
    }

    public int markReviewed(@NotNull Collection<VirtualFile> files) {
        int marked = 0;
        for (VirtualFile file : files) {
            Entry entry = entries.get(normalize(file.getPath()));
            if (entry == null) continue;
            Computed computed = entry.computed;
            if (!reviewable(computed)) continue;
            store.mark(entry.storeKey, Objects.requireNonNull(computed.fingerprint()));
            entry.computed = new Computed(computed.fingerprint(), computed.stats(),
                    ReviewStatus.REVIEWED, "已审阅");
            marked++;
        }
        if (marked > 0) requestUiRefresh();
        return marked;
    }

    public int unmarkReviewed(@NotNull Collection<VirtualFile> files) {
        int changed = 0;
        for (VirtualFile file : files) {
            Entry entry = entries.get(normalize(file.getPath()));
            if (entry == null || !store.contains(entry.storeKey)) continue;
            store.unmark(entry.storeKey);
            Computed computed = entry.computed;
            if (computed != null && computed.status() != ReviewStatus.UNREVIEWABLE) {
                entry.computed = new Computed(computed.fingerprint(), computed.stats(),
                        ReviewStatus.UNREVIEWED, "未审阅");
            }
            changed++;
        }
        if (changed > 0) requestUiRefresh();
        return changed;
    }

    public void resetReviews() {
        store.clearReviews();
        for (Entry entry : entries.values()) {
            Computed computed = entry.computed;
            if (computed != null && computed.status() != ReviewStatus.UNREVIEWABLE) {
                entry.computed = new Computed(computed.fingerprint(), computed.stats(),
                        ReviewStatus.UNREVIEWED, "未审阅");
            }
        }
        requestUiRefresh();
    }

    public boolean markAndOpenNext(@NotNull VirtualFile current) {
        if (markReviewed(List.of(current)) == 0) return false;
        return openNextUnreviewed(current);
    }

    public boolean openNextUnreviewed(@Nullable VirtualFile current) {
        Entry next = findNextUnreviewed(current == null ? null : normalize(current.getPath()));
        if (next == null) return false;
        openForReview(next);
        return true;
    }

    public boolean hasNextUnreviewed() {
        return findNextUnreviewed(null) != null;
    }

    public Progress progress() {
        int reviewed = 0;
        int needsReview = 0;
        int skipped = 0;
        int loading = 0;
        int reviewable = 0;
        Map<String, Entry> current = entries;
        for (Entry entry : current.values()) {
            Computed computed = entry.computed;
            if (computed == null) {
                loading++;
                continue;
            }
            switch (computed.status()) {
                case REVIEWED -> {
                    reviewed++;
                    reviewable++;
                }
                case NEEDS_REVIEW -> {
                    needsReview++;
                    reviewable++;
                }
                case UNREVIEWED -> reviewable++;
                case UNREVIEWABLE -> skipped++;
            }
        }
        return new Progress(current.size(), reviewable, reviewed, needsReview, skipped, loading);
    }

    public int storedReviewCount() {
        return store.size();
    }

    private void scheduleRefresh() {
        if (disposed || !started.get()) return;
        if (!refreshScheduled.compareAndSet(false, true)) return;
        workers.execute(() -> {
            try {
                refreshScope();
            } finally {
                refreshScheduled.set(false);
            }
        });
    }

    private void refreshScope() {
        if (disposed || project.isDisposed()) return;

        Map<String, Entry> previous = entries;
        Map<String, Entry> next = new LinkedHashMap<>();

        for (Change change : changeListManager.getAllChanges()) {
            addChange(next, previous, change);
        }

        for (FilePath path : changeListManager.getUnversionedFilesPaths()) {
            VirtualFile file = path.getVirtualFile();
            if (file == null || !file.isValid()) continue;
            if (file.isDirectory()) {
                VfsUtilCore.iterateChildrenRecursively(file,
                        child -> !changeListManager.isIgnoredFile(child),
                        child -> {
                            if (!child.isDirectory()) addUnversioned(next, previous, child);
                            return true;
                        });
            } else {
                addUnversioned(next, previous, file);
            }
        }

        entries = Map.copyOf(next);
        for (Entry entry : next.values()) {
            if (entry.computed == null) {
                workers.execute(() -> compute(entry));
            }
        }
        requestUiRefresh();
    }

    private void addChange(Map<String, Entry> next, Map<String, Entry> previous, Change change) {
        ContentRevision before = change.getBeforeRevision();
        ContentRevision after = change.getAfterRevision();
        FilePath path = after != null ? after.getFile() : before != null ? before.getFile() : null;
        if (path == null || path.isDirectory()) return;

        String normalizedPath = normalize(path.getPath());
        VirtualFile file = after != null ? after.getFile().getVirtualFile() : null;
        boolean deleted = after == null;
        String root = vcsRoot(path);
        String beforeRevision = revision(before);
        String signature = "change|" + beforeRevision + "|" + revision(after) + "|"
                + normalizedPath + "|" + currentStamp(file) + "|" + change.getType();
        String storeKey = root + "\u0000" + normalizedPath;

        Entry entry = new Entry(normalizedPath, storeKey, root, beforeRevision, signature,
                file, change, false, deleted);
        reuse(previous.get(normalizedPath), entry);
        if (deleted && entry.computed == null) {
            entry.computed = new Computed(null, null, ReviewStatus.UNREVIEWABLE,
                    "已删除文件在 Project View 中没有文件节点");
        }
        next.put(normalizedPath, entry);
    }

    private void addUnversioned(Map<String, Entry> next, Map<String, Entry> previous, VirtualFile file) {
        String normalizedPath = normalize(file.getPath());
        if (next.containsKey(normalizedPath)) return;
        String root = vcsRoot(file);
        String signature = "unversioned|" + normalizedPath + "|" + currentStamp(file) + "|" + file.getLength();
        String storeKey = root + "\u0000" + normalizedPath;
        Entry entry = new Entry(normalizedPath, storeKey, root, "<unversioned>", signature,
                file, null, true, false);
        reuse(previous.get(normalizedPath), entry);
        next.put(normalizedPath, entry);
    }

    private static void reuse(@Nullable Entry old, Entry replacement) {
        if (old != null && old.signature.equals(replacement.signature)) {
            replacement.computed = old.computed;
        }
    }

    private void compute(Entry entry) {
        if (disposed || entry.deleted) return;
        try {
            if (binary(entry)) {
                publish(entry, new Computed(null, null, ReviewStatus.UNREVIEWABLE, "二进制文件"));
                return;
            }

            String before;
            String after;
            if (entry.unversioned) {
                before = "";
                after = currentText(Objects.requireNonNull(entry.file));
            } else {
                Change change = Objects.requireNonNull(entry.change);
                before = revisionText(change.getBeforeRevision());
                after = revisionText(change.getAfterRevision());
            }

            if (before.length() > MAX_TEXT_CHARS || after.length() > MAX_TEXT_CHARS) {
                publish(entry, new Computed(null, null, ReviewStatus.UNREVIEWABLE, "文件过大"));
                return;
            }

            String fingerprint = ReviewFingerprint.calculate(
                    entry.vcsRoot, entry.path, entry.beforeRevision, before, after);

            LineDiff.Stats stats = null;
            try {
                stats = LineDiff.calculate(before, after, () -> {
                    if (Thread.currentThread().isInterrupted()) throw new CancellationException();
                });
            } catch (LineDiff.LimitExceededException ignored) {
                // Fingerprint is still valid. A complex diff can be reviewed even if line stats are omitted.
            }

            String stored = store.fingerprint(entry.storeKey);
            ReviewStatus status = stored == null
                    ? ReviewStatus.UNREVIEWED
                    : stored.equals(fingerprint) ? ReviewStatus.REVIEWED : ReviewStatus.NEEDS_REVIEW;
            String detail = switch (status) {
                case UNREVIEWED -> "未审阅";
                case REVIEWED -> "已审阅";
                case NEEDS_REVIEW -> "内容已变化，需要重新审阅";
                case UNREVIEWABLE -> "不可审阅";
            };
            publish(entry, new Computed(fingerprint, stats, status, detail));
        } catch (CancellationException ignored) {
            // Project is closing or this work was interrupted.
        } catch (VcsException | RuntimeException failure) {
            publish(entry, new Computed(null, null, ReviewStatus.UNREVIEWABLE, "无法读取文件内容"));
        }
    }

    private void publish(Entry entry, Computed computed) {
        if (disposed) return;
        Entry current = entries.get(entry.path);
        if (current != entry) return;
        entry.computed = computed;
        requestUiRefresh();
    }

    private boolean binary(Entry entry) {
        if (entry.file != null && entry.file.getFileType().isBinary()) return true;
        Change change = entry.change;
        if (change == null) return false;
        ContentRevision before = change.getBeforeRevision();
        ContentRevision after = change.getAfterRevision();
        return before != null && before.getFile().getFileType().isBinary()
                || after != null && after.getFile().getFileType().isBinary();
    }

    private String currentText(VirtualFile file) {
        if (file.getLength() > MAX_UNVERSIONED_BYTES) {
            throw new IllegalArgumentException("File too large");
        }
        return ReadAction.nonBlocking(() -> {
            Document document = FileDocumentManager.getInstance().getCachedDocument(file);
            if (document != null) return document.getText();
            try {
                return VfsUtilCore.loadText(file);
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        }).expireWith(this).executeSynchronously();
    }

    private static String revisionText(@Nullable ContentRevision revision) throws VcsException {
        if (revision == null) return "";
        String content = revision.getContent();
        if (content == null) throw new VcsException("Revision content is unavailable");
        return content;
    }

    private void invalidate(String rawPath) {
        Entry entry = entries.get(normalize(rawPath));
        if (entry == null) return;
        Computed computed = entry.computed;
        if (computed != null && computed.status() == ReviewStatus.REVIEWED) {
            entry.computed = new Computed(computed.fingerprint(), computed.stats(),
                    ReviewStatus.NEEDS_REVIEW, "内容已变化，正在重新计算");
            requestUiRefresh();
        }
    }

    private @Nullable Entry findNextUnreviewed(@Nullable String currentPath) {
        List<Entry> candidates = new ArrayList<>();
        for (Entry entry : entries.values()) {
            Computed computed = entry.computed;
            if (entry.file == null || computed == null) continue;
            if (computed.status() == ReviewStatus.UNREVIEWED || computed.status() == ReviewStatus.NEEDS_REVIEW) {
                candidates.add(entry);
            }
        }
        if (candidates.isEmpty()) return null;
        candidates.sort(Comparator.comparing(entry -> entry.path));
        if (currentPath == null) return candidates.getFirst();
        for (Entry entry : candidates) {
            if (entry.path.compareTo(currentPath) > 0) return entry;
        }
        return candidates.getFirst();
    }

    private void openForReview(Entry entry) {
        Runnable open = () -> {
            if (disposed || project.isDisposed() || entry.file == null || !entry.file.isValid()) return;
            ProjectView.getInstance(project).select(entry.file, entry.file, false);
            if (entry.change != null && ShowDiffAction.canShowDiff(project, List.of(entry.change))) {
                ShowDiffAction.showDiffForChange(project, List.of(entry.change));
            } else {
                FileEditorManager.getInstance(project).openFile(entry.file, true);
            }
        };
        if (ApplicationManager.getApplication().isDispatchThread()) open.run();
        else ApplicationManager.getApplication().invokeLater(open);
    }

    private String vcsRoot(FilePath path) {
        VirtualFile root = ProjectLevelVcsManager.getInstance(project).getVcsRootFor(path);
        return root != null ? normalize(root.getPath()) : projectBase();
    }

    private String vcsRoot(VirtualFile file) {
        VirtualFile root = ProjectLevelVcsManager.getInstance(project).getVcsRootFor(file);
        return root != null ? normalize(root.getPath()) : projectBase();
    }

    private String projectBase() {
        String base = project.getBasePath();
        return base == null ? "<project>" : normalize(base);
    }

    private static String revision(@Nullable ContentRevision revision) {
        if (revision == null) return "<none>";
        return revision.getRevisionNumber().asString() + "|" + normalize(revision.getFile().getPath());
    }

    private static long currentStamp(@Nullable VirtualFile file) {
        if (file == null || !file.isValid()) return -1;
        Document document = FileDocumentManager.getInstance().getCachedDocument(file);
        return document != null ? document.getModificationStamp() : file.getModificationStamp();
    }

    private static boolean reviewable(@Nullable Computed computed) {
        return computed != null
                && computed.fingerprint() != null
                && computed.status() != ReviewStatus.UNREVIEWABLE;
    }

    private void requestUiRefresh() {
        if (disposed || !uiRefreshScheduled.compareAndSet(false, true)) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            uiRefreshScheduled.set(false);
            if (!disposed && !project.isDisposed()) {
                ProjectView.getInstance(project).refresh();
            }
        });
    }

    private static String normalize(String path) {
        return FileUtil.toSystemIndependentName(path);
    }

    @Override
    public void dispose() {
        disposed = true;
        workers.shutdownNow();
        entries = Map.of();
    }
}
