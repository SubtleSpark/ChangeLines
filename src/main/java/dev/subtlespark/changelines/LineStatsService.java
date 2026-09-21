package dev.subtlespark.changelines;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.BinaryContentRevision;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.changes.CurrentContentRevision;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded per-project cache keyed by actual Change identity, never by working-copy path alone. */
@Service(Service.Level.PROJECT)
public final class LineStatsService implements Disposable {
    private static final Logger LOG = Logger.getInstance(LineStatsService.class);
    private static final int CACHE_SIZE = 2_048;
    private static final long RETRY_NANOS = TimeUnit.SECONDS.toNanos(10);

    public enum State { READY, LOADING, BINARY, LIMITED, UNAVAILABLE }
    public record Result(State state, LineDiff.Stats stats, String fingerprint) {
        public Result(State state, LineDiff.Stats stats) { this(state, stats, null); }
        static Result of(State state) { return new Result(state, null, null); }
    }

    private final Project project;
    private final ChangeLinesInstaller installer;
    private final AtomicLong workingGeneration = new AtomicLong();
    private final Map<IdentityKey, Entry> cache = new LinkedHashMap<>(64, 0.75f, true);
    private final ThreadPoolExecutor workers;
    private volatile boolean disposed;

    public LineStatsService(Project project) {
        this.project = project;
        installer = ApplicationManager.getApplication().getService(ChangeLinesInstaller.class);
        AtomicInteger sequence = new AtomicInteger();
        workers = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(256), task -> {
            Thread thread = new Thread(task, "ChangeLines-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        workers.allowCoreThreadTimeOut(true);
        EditorFactory.getInstance().getEventMulticaster().addDocumentListener(new DocumentListener() {
            @Override public void documentChanged(@NotNull DocumentEvent event) { invalidateWorkingTree(); }
        }, this);
        project.getMessageBus().connect(this).subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
            @Override public void after(@NotNull List<? extends VFileEvent> events) {
                if (!events.isEmpty()) invalidateWorkingTree();
            }
        });
    }

    void invalidateWorkingTree() {
        workingGeneration.incrementAndGet();
        installer.requestRefresh();
    }

    public synchronized Result get(Change change, boolean allowLoading) {
        if (disposed || project.isDisposed()) return Result.of(State.UNAVAILABLE);
        IdentityKey key = new IdentityKey(change);
        Entry entry = cache.get(key);
        long generation = workingGeneration.get();
        boolean live = change.getBeforeRevision() instanceof CurrentContentRevision
                || change.getAfterRevision() instanceof CurrentContentRevision;
        if (entry != null && ((live && entry.generation != generation)
                || (entry.result.state() == State.UNAVAILABLE && System.nanoTime() - entry.completedAt > RETRY_NANOS))) {
            cache.remove(key);
            entry.cancel();
            entry = null;
        }
        if (entry != null) return entry.result;
        if (!allowLoading) return Result.of(State.LOADING);
        Entry submitted = new Entry(generation, live);
        cache.put(key, submitted);
        while (cache.size() > CACHE_SIZE) {
            Iterator<Entry> iterator = cache.values().iterator();
            iterator.next().cancel();
            iterator.remove();
        }
        try {
            submitted.future = workers.submit(() -> calculate(key, submitted));
        } catch (RejectedExecutionException ignored) {
            // Never fall back to running revision IO on the EDT.
            cache.remove(key);
        }
        return submitted.result;
    }

    private void calculate(IdentityKey key, Entry entry) {
        try {
            ProgressManager.getInstance().runProcess(() -> {
                Result result;
                String fingerprint = null;
                try {
                    Change change = key.change;
                    if (binary(change.getBeforeRevision(), entry) || binary(change.getAfterRevision(), entry)) {
                        result = Result.of(State.BINARY);
                    } else {
                        String before = content(change.getBeforeRevision());
                        String after = content(change.getAfterRevision());
                        if (before.indexOf('\0') >= 0 || after.indexOf('\0') >= 0) {
                            result = Result.of(State.BINARY);
                        } else {
                            fingerprint = ReviewFingerprint.content(change.getBeforeRevision() == null ? null : before,
                                    change.getAfterRevision() == null ? null : after);
                            ProgressManager.checkCanceled();
                            LineDiff.Stats stats = LineDiff.calculate(before, after, () -> {
                                ProgressManager.checkCanceled();
                                if (Thread.currentThread().isInterrupted()) throw new CancellationException();
                            });
                            result = new Result(State.READY, stats, fingerprint);
                        }
                    }
                } catch (LineDiff.LimitExceededException ignored) {
                    result = new Result(State.LIMITED, null, fingerprint);
                } catch (CancellationException cancelled) {
                    throw cancelled;
                } catch (VcsException | RuntimeException failure) {
                    LOG.debug("ChangeLines could not load or compare revision content", failure);
                    result = Result.of(State.UNAVAILABLE);
                }
                synchronized (this) {
                    if (!disposed && cache.get(key) == entry) {
                        if (entry.live && entry.generation != workingGeneration.get()) cache.remove(key);
                        else {
                            entry.result = result;
                            entry.completedAt = System.nanoTime();
                        }
                    }
                }
            }, entry.indicator);
        } catch (CancellationException ignored) {
            synchronized (this) {
                if (cache.get(key) == entry) cache.remove(key);
            }
        } finally {
            if (!disposed) installer.requestRefresh();
        }
    }

    private boolean binary(ContentRevision revision, Entry entry) {
        if (revision == null) return false;
        if (revision instanceof BinaryContentRevision) return true;
        return ReadAction.nonBlocking(() -> revision.getFile().getFileType().isBinary())
                .expireWith(this).wrapProgress(entry.indicator).executeSynchronously();
    }

    private static String content(ContentRevision revision) throws VcsException {
        ProgressManager.checkCanceled();
        if (revision == null) return "";
        String text = revision.getContent();
        if (text == null) throw new VcsException("Revision content is unavailable");
        if (text.length() > LineDiff.MAX_CHARS) throw new LineDiff.LimitExceededException();
        return text;
    }

    @Override public synchronized void dispose() {
        disposed = true;
        cache.values().forEach(Entry::cancel);
        cache.clear();
        workers.shutdownNow();
    }

    private static final class IdentityKey {
        private final Change change;
        private IdentityKey(Change change) { this.change = change; }
        @Override public int hashCode() { return System.identityHashCode(change); }
        @Override public boolean equals(Object other) {
            return other instanceof IdentityKey key && key.change == change;
        }
    }

    private static final class Entry {
        private final long generation;
        private final boolean live;
        private final EmptyProgressIndicator indicator = new EmptyProgressIndicator();
        private Result result = Result.of(State.LOADING);
        private long completedAt;
        private Future<?> future;
        private Entry(long generation, boolean live) { this.generation = generation; this.live = live; }
        private void cancel() {
            indicator.cancel();
            if (future != null) future.cancel(true);
        }
    }
}
