package dev.subtlespark.changelines;

import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.changes.CurrentContentRevision;
import com.intellij.openapi.vcs.changes.ui.ChangesTree;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;

import javax.swing.SwingUtilities;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/** Conservative comparison identity. Never infer a review context from the file name alone. */
final class ReviewScope {
    private ReviewScope() {}

    static String resolve(ChangesTree tree, List<Change> changes, String temporaryScope) {
        String windowId = "";
        String title = "";
        var manager = ToolWindowManager.getInstance(tree.getProject());
        for (String id : manager.getToolWindowIds()) {
            var window = manager.getToolWindow(id);
            if (window == null || !window.isVisible()
                    || !SwingUtilities.isDescendingFrom(tree, window.getComponent())) continue;
            windowId = id;
            for (Content content : window.getContentManager().getContents()) {
                if (SwingUtilities.isDescendingFrom(tree, content.getComponent())) {
                    title = Objects.requireNonNullElse(content.getTabName(), content.getDisplayName());
                    break;
                }
            }
            break;
        }
        String project = Objects.requireNonNullElse(tree.getProject().getBasePath(), tree.getProject().getLocationHash());
        return resolve(project, tree.getClass().getName(), windowId, title, changes, temporaryScope);
    }

    static String resolve(String project, String treeType, String windowId, String title,
                          List<Change> changes, String temporaryScope) {
        // The native Changes Between tool window keeps both compared ref names in the
        // full tab title (the visual ellipsis does not truncate this property). A new
        // commit on the same refs does not discard approvals for unchanged contents.
        if ("VcsChanges".equals(windowId) && title != null && !title.isBlank()) {
            return ReviewFingerprint.hash("ChangeLines.comparison.v1", project, windowId, title);
        }
        TreeSet<String> revisions = new TreeSet<>();
        boolean known = false;
        boolean working = false;
        for (Change change : changes) {
            ContentRevision before = change.getBeforeRevision();
            ContentRevision after = change.getAfterRevision();
            working |= live(change);
            String left = revision(before);
            String right = revision(after);
            known |= left != null && !left.equals("working") || right != null && !right.equals("working");
            revisions.add(ReviewFingerprint.hash(left, right));
        }
        // Generic Git Log views reuse a tree for different commits. Include actual
        // revisions, not just the tab title. Unknown third-party comparisons fail
        // closed to a temporary, per-tree context rather than sharing approvals.
        if (!known && !working) return temporaryScope;
        return ReviewFingerprint.hash("ChangeLines.revisions.v1", project, treeType,
                windowId, title, String.join(";", revisions));
    }

    static String file(Change change) {
        return ReviewFingerprint.hash(path(change.getBeforeRevision()), path(change.getAfterRevision()));
    }

    static boolean live(Change change) {
        return change.getBeforeRevision() instanceof CurrentContentRevision
                || change.getAfterRevision() instanceof CurrentContentRevision;
    }

    private static String path(ContentRevision revision) {
        return revision == null ? null : revision.getFile().getPath();
    }

    private static String revision(ContentRevision revision) {
        if (revision == null) return null;
        if (revision instanceof CurrentContentRevision) return "working";
        VcsRevisionNumber number = revision.getRevisionNumber();
        if (number == null || number == VcsRevisionNumber.NULL || number.asString().isBlank()) return null;
        return number.asString();
    }
}
