package dev.subtlespark.changelines;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode;
import com.intellij.openapi.vcs.changes.ui.ChangesTree;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.testFramework.LightPlatformTestCase;
import com.intellij.util.ui.UIUtil;
import com.intellij.vcsUtil.VcsUtil;

import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.util.concurrent.atomic.AtomicInteger;

public final class FolderNavigationTest extends LightPlatformTestCase {
    public void testSelectingAncestorCancelsQueuedFileNavigation() {
        var file = VcsUtil.getFilePath("/changelines-folder-nav/File.txt", false);
        Change change = new Change(null, new ContentRevision() {
            @Override public String getContent() { return "line\n"; }
            @Override public FilePath getFile() { return file; }
            @Override public VcsRevisionNumber getRevisionNumber() { return VcsRevisionNumber.NULL; }
        });
        ChangesTree tree = new ChangesTree(getProject(), false, false) {
            @Override public void rebuildTree() { }
        };
        var root = ChangesBrowserNode.createRoot();
        var folder = ChangesBrowserNode.createFilePath(VcsUtil.getFilePath("/changelines-folder-nav", true));
        folder.add(ChangesBrowserNode.createChange(getProject(), change));
        root.add(folder);
        tree.setModel(new DefaultTreeModel(root));
        var installer = ApplicationManager.getApplication().getService(ChangeLinesInstaller.class);
        installer.attach(tree);
        try {
            var session = (ReviewSession) tree.getClientProperty(ReviewSession.PROPERTY);
            AtomicInteger opened = new AtomicInteger();
            tree.setDoubleClickAndEnterKeyHandler(opened::incrementAndGet);
            session.open(change);
            tree.setSelectionPath(new TreePath(folder.getPath()));
            UIUtil.dispatchAllInvocationEvents();
            assertEquals(0, opened.get());
            session.open(change);
            UIUtil.dispatchAllInvocationEvents();
            assertEquals(1, opened.get());
        } finally { installer.detach(tree); }
    }
}
