package dev.subtlespark.changelines;

import com.intellij.diff.DiffContext;
import com.intellij.diff.DiffExtension;
import com.intellij.diff.FrameDiffTool;
import com.intellij.diff.requests.DiffRequest;
import com.intellij.diff.util.DiffUserDataKeys;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.actions.diff.ChangeDiffRequestProducer;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** Adds review controls to native Diff viewers only when their actual Change has an unambiguous owner. */
public final class ReviewDiffExtension extends DiffExtension {
    @Override public void onViewerCreated(@NotNull FrameDiffTool.DiffViewer viewer,
            @NotNull DiffContext context, @NotNull DiffRequest request) {
        Change change = request.getUserData(ChangeDiffRequestProducer.CHANGE_KEY);
        if (change == null) return;
        var installer = ApplicationManager.getApplication().getService(ChangeLinesInstaller.class);
        ReviewSession session = installer.sessionFor(change);
        if (session != null) installActions(request, session, change);
    }

    static void installActions(DiffRequest request, ReviewSession session, Change change) {
        List<AnAction> actions = new ArrayList<>();
        List<AnAction> existing = request.getUserData(DiffUserDataKeys.CONTEXT_ACTIONS);
        if (existing != null) for (AnAction action : existing) {
            if (!(action instanceof ReviewActions.BoundAction)) actions.add(action);
        }
        actions.add(new ReviewActions.BoundAction(session, change, ReviewActions.Kind.TOGGLE));
        actions.add(new ReviewActions.BoundAction(session, change, ReviewActions.Kind.MARK_NEXT));
        request.putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, actions);
    }
}
