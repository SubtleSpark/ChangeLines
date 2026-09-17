package dev.subtlespark.changelines;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ui.ChangesTree;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.lang.ref.WeakReference;
import java.util.List;

/** Native-context actions: explicit review, never 'opened means reviewed'. */
public final class ReviewActions {
    private ReviewActions() {}
    enum Kind { MARK, UNMARK, MARK_NEXT, NEXT, TOGGLE }

    static ReviewSession from(Component component) {
        if (component == null) return null;
        ChangesTree tree = component instanceof ChangesTree t ? t
                : (ChangesTree) SwingUtilities.getAncestorOfClass(ChangesTree.class, component);
        if (tree == null || !(tree.getClientProperty(ReviewSession.PROPERTY) instanceof ReviewSession session)) return null;
        return session.isActive() ? session : null;
    }

    static void decoratePopup(JPopupMenu popup) {
        ReviewSession session = from(popup.getInvoker());
        if (session == null) return;
        // Native popup instances can be cleared and reused. Check the actual child,
        // not a flag on the popup that would survive removeAll().
        for (Component child : popup.getComponents()) {
            if (child instanceof JComponent component
                    && Boolean.TRUE.equals(component.getClientProperty("ChangeLines.reviewMenu"))) return;
        }
        JMenu review = new JMenu("ChangeLines 审阅");
        review.putClientProperty("ChangeLines.reviewMenu", Boolean.TRUE);
        fill(review.getPopupMenu(), session);
        popup.addSeparator();
        popup.add(review);
    }

    static JPopupMenu menu(ReviewSession session) {
        JPopupMenu menu = new JPopupMenu();
        fill(menu, session);
        return menu;
    }

    private static void fill(JPopupMenu menu, ReviewSession session) {
        List<Change> selected = session.selected();
        for (Kind kind : new Kind[]{Kind.MARK, Kind.UNMARK, Kind.MARK_NEXT, Kind.NEXT}) {
            JMenuItem item = new JMenuItem(text(kind));
            item.setEnabled(enabled(session, selected, kind));
            if (!item.isEnabled() && (kind == Kind.MARK || kind == Kind.MARK_NEXT)) {
                item.setToolTipText("请选择文件并等待统计完成；首版不标记二进制、无法读取或超出内容大小限制的文件。");
            }
            String scope = session.scope();
            item.addActionListener(e -> {
                if (session.isActive() && scope.equals(session.scope())) perform(session, selected, kind);
            });
            menu.add(item);
        }
    }

    static boolean enabled(ReviewSession session, List<Change> selected, Kind kind) {
        if (!session.isActive()) return false;
        return switch (kind) {
            case MARK -> session.canMark(selected);
            case UNMARK -> session.hasMarks(selected);
            case MARK_NEXT -> selected.size() == 1 && session.canMark(selected);
            case NEXT -> session.progress().total() > 0;
            case TOGGLE -> selected.size() == 1 && (session.status(selected.getFirst()) == ReviewSession.Status.REVIEWED
                    || session.canMark(selected));
        };
    }

    static void perform(ReviewSession session, List<Change> selected, Kind kind) {
        if (!session.isActive()) return;
        if (kind == Kind.UNMARK || kind == Kind.TOGGLE && selected.size() == 1
                && session.status(selected.getFirst()) == ReviewSession.Status.REVIEWED) {
            session.unmark(selected);
            return;
        }
        if (kind == Kind.MARK || kind == Kind.MARK_NEXT || kind == Kind.TOGGLE) {
            if (!session.mark(selected)) {
                Messages.showInfoMessage(session.project(), "内容正在核对或无法统计，本次没有写入任何已审阅标记。请等待统计完成后再试。", "ChangeLines");
                return;
            }
        }
        if (kind == Kind.MARK_NEXT || kind == Kind.NEXT) {
            Change current = selected.isEmpty() ? null : selected.getLast();
            Change next = session.nextAfter(current);
            if (next != null) session.open(next);
            else Messages.showInfoMessage(session.project(), "当前比较中的文件均已审阅。", "ChangeLines");
        }
    }

    private static String text(Kind kind) {
        return switch (kind) {
            case MARK, TOGGLE -> "标记已审阅";
            case UNMARK -> "取消已审阅";
            case MARK_NEXT -> "标记并打开下一个未审阅文件";
            case NEXT -> "打开下一个未审阅文件";
        };
    }

    public abstract static class TreeAction extends DumbAwareAction {
        private final Kind kind;
        protected TreeAction(Kind kind) { this.kind = kind; }
        @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
        @Override public void update(@NotNull AnActionEvent e) {
            ReviewSession session = from(e.getData(PlatformDataKeys.CONTEXT_COMPONENT));
            e.getPresentation().setEnabledAndVisible(session != null);
            if (session != null) e.getPresentation().setEnabled(enabled(session, session.selected(), kind));
        }
        @Override public void actionPerformed(@NotNull AnActionEvent e) {
            ReviewSession session = from(e.getData(PlatformDataKeys.CONTEXT_COMPONENT));
            if (session != null) perform(session, session.selected(), kind);
        }
    }

    public static final class Mark extends TreeAction { public Mark() { super(Kind.MARK); } }
    public static final class Unmark extends TreeAction { public Unmark() { super(Kind.UNMARK); } }
    public static final class MarkNext extends TreeAction { public MarkNext() { super(Kind.MARK_NEXT); } }
    public static final class Next extends TreeAction { public Next() { super(Kind.NEXT); } }

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
                e.getPresentation().setText(session.status(change) == ReviewSession.Status.REVIEWED ? "已审阅（点击取消）" : "标记已审阅");
            }
        }
        @Override public void actionPerformed(@NotNull AnActionEvent e) {
            ReviewSession session = session();
            if (session != null) perform(session, List.of(change), kind);
        }
    }
}
