package dev.subtlespark.changelines;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.DumbAwareToggleAction;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ui.ChangesTree;
import org.jetbrains.annotations.NotNull;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.lang.ref.WeakReference;
import java.util.List;

/** Every menu/toolbar uses the Action System. No hand-built Swing popup menus. */
public final class ReviewActions {
    private ReviewActions() {}
    enum Kind { MARK, UNMARK, MARK_NEXT, NEXT, TOGGLE, RESET }

    static ReviewSession from(Component component) {
        if (component == null) return null;
        ChangesTree tree = component instanceof ChangesTree t ? t
                : (ChangesTree) SwingUtilities.getAncestorOfClass(ChangesTree.class, component);
        if (tree == null || !(tree.getClientProperty(ReviewSession.PROPERTY) instanceof ReviewSession session)) return null;
        return session.isActive() ? session : null;
    }

    static DefaultActionGroup toolbarGroup(ReviewSession session) {
        DefaultActionGroup group = new DefaultActionGroup();
        group.add(new ProgressAction(session));
        group.add(new SessionAction(session, Kind.TOGGLE));
        group.add(new SessionAction(session, Kind.MARK_NEXT));
        group.add(new SessionAction(session, Kind.NEXT));
        DefaultActionGroup more = new DefaultActionGroup("审阅", true);
        more.getTemplatePresentation().putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true);
        more.add(new SessionAction(session, Kind.MARK));
        more.add(new SessionAction(session, Kind.UNMARK));
        more.addSeparator();
        more.add(new FolderSummaryToggleAction(session));
        more.addSeparator();
        more.add(new SessionAction(session, Kind.RESET));
        group.add(more);
        return group;
    }

    private static final class FolderSummaryToggleAction extends DumbAwareToggleAction {
        private final WeakReference<ReviewSession> reference;

        FolderSummaryToggleAction(ReviewSession session) {
            super("显示文件夹汇总", "显示文件夹的增删合计和子文件审阅进度；关闭不影响文件统计或批量审阅。", null);
            reference = new WeakReference<>(session);
        }

        @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }

        @Override public boolean isSelected(@NotNull AnActionEvent e) {
            ReviewSession session = reference.get();
            return session != null && session.isActive() && FolderSummarySettings.isVisible(session.project());
        }

        @Override public void setSelected(@NotNull AnActionEvent e, boolean state) {
            ReviewSession session = reference.get();
            if (session != null && session.isActive()) FolderSummarySettings.setVisible(session.project(), state);
        }

        @Override public void update(@NotNull AnActionEvent e) {
            super.update(e);
            ReviewSession session = reference.get();
            e.getPresentation().setEnabledAndVisible(session != null && session.isActive());
        }
    }

    static boolean enabled(ReviewSession session, List<Change> selected, Kind kind) {
        if (!session.isActive()) return false;
        return switch (kind) {
            case MARK -> session.canMark(selected);
            case UNMARK -> session.hasMarks(selected);
            case MARK_NEXT -> !selected.isEmpty() && session.canMark(selected);
            case NEXT -> session.nextAfter(selected.isEmpty() ? null : selected.getLast()) != null;
            case TOGGLE -> !selected.isEmpty() && (allReviewed(session, selected) || session.canMark(selected));
            case RESET -> session.hasMarks(session.changes());
        };
    }

    private static boolean allReviewed(ReviewSession session, List<Change> selected) {
        return !selected.isEmpty() && selected.stream().allMatch(c -> session.cachedStatus(c) == ReviewSession.Status.REVIEWED);
    }

    private static List<Change> selection(ReviewSession session, Kind kind) {
        return switch (kind) {
            case MARK, MARK_NEXT, TOGGLE -> session.selectedForReview();
            default -> session.selected();
        };
    }

    /** Tree/toolbar actions expand folders. Bound Diff actions deliberately bypass this. */
    static void performSelected(ReviewSession session, Kind kind) {
        List<Change> selected = selection(session, kind);
        if (kind == Kind.TOGGLE && allReviewed(session, selected)) {
            // Remove all marks in this selection, including old marks on now-unsupported files.
            perform(session, session.selected(), Kind.UNMARK);
        } else {
            perform(session, selected, kind);
        }
    }

    static void perform(ReviewSession session, List<Change> selected, Kind kind) {
        if (!session.isActive()) return;
        if (kind == Kind.RESET) {
            String scope = session.scope();
            int answer = Messages.showYesNoDialog(session.project(), "取消当前比较中所有文件的审阅标记？其他比较不受影响。",
                    "ChangeLines", Messages.getQuestionIcon());
            if (answer == Messages.YES && session.isActive() && scope.equals(session.scope())) session.unmark(session.changes());
            return;
        }
        if (kind == Kind.UNMARK || kind == Kind.TOGGLE && allReviewed(session, selected)) {
            session.unmark(selected);
            return;
        }
        if (kind == Kind.MARK || kind == Kind.MARK_NEXT || kind == Kind.TOGGLE) {
            if (!session.mark(selected)) {
                Messages.showInfoMessage(session.project(), "内容正在核对或无法读取，本次没有写入任何已审阅标记。请等待核对完成。", "ChangeLines");
                return;
            }
        }
        if (kind == Kind.MARK_NEXT || kind == Kind.NEXT) {
            Change next = session.nextAfter(selected.isEmpty() ? null : selected.getLast());
            if (next != null) session.open(next);
            else Messages.showInfoMessage(session.project(), "没有可继续的未审阅文件。二进制和无法读取的文件已跳过。", "ChangeLines");
        }
    }

    private static String text(Kind kind) {
        return switch (kind) {
            case MARK, TOGGLE -> "标记已审阅";
            case UNMARK -> "取消已审阅";
            case MARK_NEXT -> "审阅并下一个";
            case NEXT -> "下一个未审阅";
            case RESET -> "重置当前比较";
        };
    }

    public abstract static class TreeAction extends DumbAwareAction {
        private final Kind kind;
        protected TreeAction(Kind kind) { this.kind = kind; }
        @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
        @Override public void update(@NotNull AnActionEvent e) {
            ReviewSession session = from(e.getData(PlatformDataKeys.CONTEXT_COMPONENT));
            e.getPresentation().setEnabledAndVisible(session != null);
            if (session != null) e.getPresentation().setEnabled(enabled(session, selection(session, kind), kind));
        }
        @Override public void actionPerformed(@NotNull AnActionEvent e) {
            ReviewSession session = from(e.getData(PlatformDataKeys.CONTEXT_COMPONENT));
            if (session != null) performSelected(session, kind);
        }
    }

    public static final class Mark extends TreeAction { public Mark() { super(Kind.MARK); } }
    public static final class Unmark extends TreeAction { public Unmark() { super(Kind.UNMARK); } }
    public static final class MarkNext extends TreeAction { public MarkNext() { super(Kind.MARK_NEXT); } }
    public static final class Next extends TreeAction { public Next() { super(Kind.NEXT); } }
    public static final class Reset extends TreeAction { public Reset() { super(Kind.RESET); } }

    private static final class SessionAction extends DumbAwareAction {
        private final WeakReference<ReviewSession> reference;
        private final Kind kind;
        SessionAction(ReviewSession session, Kind kind) {
            super(text(kind), "ChangeLines：操作此 Changes 列表的选择，不跟随其他窗口的焦点。", null);
            reference = new WeakReference<>(session);
            this.kind = kind;
            getTemplatePresentation().putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true);
        }
        @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
        @Override public void update(@NotNull AnActionEvent e) {
            ReviewSession session = reference.get();
            boolean active = session != null && session.isActive();
            e.getPresentation().setEnabledAndVisible(active);
            if (!active) return;
            List<Change> selected = selection(session, kind);
            e.getPresentation().setEnabled(enabled(session, selected, kind));
            if (kind == Kind.TOGGLE) e.getPresentation().setText(allReviewed(session, selected) ? "取消已审阅" : "标记已审阅");
            if (session.foldersSelected()) {
                int files = session.selected().size();
                int skipped = files - session.selectedForReview().size();
                e.getPresentation().setDescription("所选文件夹下 " + files + " 个变更文件（包括折叠子目录）；"
                        + "批量标记时跳过 " + skipped + " 个不可审阅文件，仍在核对的文件不会被跳过。");
            } else {
                e.getPresentation().setDescription("ChangeLines：操作此 Changes 列表的选择，不跟随其他窗口的焦点。");
            }
        }
        @Override public void actionPerformed(@NotNull AnActionEvent e) {
            ReviewSession session = reference.get();
            if (session != null && session.isActive()) performSelected(session, kind);
        }
    }

    private static final class ProgressAction extends DumbAwareAction {
        private final WeakReference<ReviewSession> reference;
        ProgressAction(ReviewSession session) {
            reference = new WeakReference<>(session);
            getTemplatePresentation().putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true);
        }
        @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
        @Override public void update(@NotNull AnActionEvent e) {
            ReviewSession session = reference.get();
            boolean active = session != null && session.isActive();
            e.getPresentation().setVisible(active);
            e.getPresentation().setEnabled(false);
            if (active) {
                e.getPresentation().setText(session.progressText());
                e.getPresentation().setDescription(session.progressDescription());
            }
        }
        @Override public void actionPerformed(@NotNull AnActionEvent e) { }
    }

    static final class BoundAction extends DumbAwareAction {
        private final WeakReference<ReviewSession> reference;
        private final Change change;
        private final String scope;
        private final Kind kind;
        BoundAction(ReviewSession session, Change change, Kind kind) {
            super(text(kind), "ChangeLines：仅标记此 Diff 中的文件，不会标记其他窗口当前选中的文件。",
                    kind == Kind.MARK_NEXT ? AllIcons.Actions.Forward : AllIcons.Actions.Checked);
            reference = new WeakReference<>(session);
            this.change = change;
            this.scope = session.scope();
            this.kind = kind;
        }
        private ReviewSession session() {
            ReviewSession session = reference.get();
            return session != null && session.contains(change) && scope.equals(session.scope()) ? session : null;
        }
        @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
        @Override public void update(@NotNull AnActionEvent e) {
            ReviewSession session = session();
            e.getPresentation().setEnabled(session != null && enabled(session, List.of(change), kind));
            if (session != null && kind == Kind.TOGGLE) {
                e.getPresentation().setText(session.cachedStatus(change) == ReviewSession.Status.REVIEWED ? "已审阅（点击取消）" : "标记已审阅");
            }
        }
        @Override public void actionPerformed(@NotNull AnActionEvent e) {
            ReviewSession session = session();
            if (session != null) perform(session, List.of(change), kind);
        }
    }
}
