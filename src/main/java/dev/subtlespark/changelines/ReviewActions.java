package dev.subtlespark.changelines;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ReviewActions {
    private ReviewActions() {}

    private static @NotNull List<VirtualFile> files(@NotNull AnActionEvent event) {
        VirtualFile[] array = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        if (array == null || array.length == 0) {
            VirtualFile file = event.getData(CommonDataKeys.VIRTUAL_FILE);
            array = file == null ? VirtualFile.EMPTY_ARRAY : new VirtualFile[]{file};
        }
        List<VirtualFile> result = new ArrayList<>();
        Arrays.stream(array)
                .filter(file -> file != null && file.isValid() && !file.isDirectory())
                .forEach(result::add);
        return result;
    }

    private static ReviewService service(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        return project == null ? null : project.getService(ReviewService.class);
    }

    private abstract static class Base extends DumbAwareAction {
        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }
    }

    public static final class Mark extends Base {
        @Override
        public void update(@NotNull AnActionEvent event) {
            ReviewService service = service(event);
            List<VirtualFile> files = files(event);
            boolean tracked = service != null && files.stream().anyMatch(service::isTracked);
            event.getPresentation().setVisible(tracked);
            event.getPresentation().setEnabled(tracked && service.canMark(files));
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent event) {
            ReviewService service = service(event);
            if (service != null) service.markReviewed(files(event));
        }
    }

    public static final class Unmark extends Base {
        @Override
        public void update(@NotNull AnActionEvent event) {
            ReviewService service = service(event);
            List<VirtualFile> files = files(event);
            boolean tracked = service != null && files.stream().anyMatch(service::isTracked);
            event.getPresentation().setVisible(tracked);
            event.getPresentation().setEnabled(tracked && service.canUnmark(files));
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent event) {
            ReviewService service = service(event);
            if (service != null) service.unmarkReviewed(files(event));
        }
    }

    public static final class MarkNext extends Base {
        @Override
        public void update(@NotNull AnActionEvent event) {
            ReviewService service = service(event);
            List<VirtualFile> files = files(event);
            boolean enabled = service != null && files.size() == 1
                    && service.isTracked(files.getFirst())
                    && service.canMark(files);
            event.getPresentation().setVisible(service != null && files.size() == 1
                    && service.isTracked(files.getFirst()));
            event.getPresentation().setEnabled(enabled);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent event) {
            ReviewService service = service(event);
            List<VirtualFile> files = files(event);
            if (service != null && files.size() == 1) service.markAndOpenNext(files.getFirst());
        }
    }

    public static final class Next extends Base {
        @Override
        public void update(@NotNull AnActionEvent event) {
            ReviewService service = service(event);
            boolean enabled = service != null && service.hasNextUnreviewed();
            event.getPresentation().setEnabled(enabled);
            event.getPresentation().setVisible(service != null && service.progress().changed() > 0);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent event) {
            ReviewService service = service(event);
            if (service == null) return;
            List<VirtualFile> files = files(event);
            service.openNextUnreviewed(files.size() == 1 ? files.getFirst() : null);
        }
    }

    public static final class Reset extends Base {
        @Override
        public void update(@NotNull AnActionEvent event) {
            ReviewService service = service(event);
            boolean visible = service != null && service.progress().changed() > 0;
            event.getPresentation().setVisible(visible);
            event.getPresentation().setEnabled(visible && service.storedReviewCount() > 0);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent event) {
            ReviewService service = service(event);
            if (service != null) service.resetReviews();
        }
    }

    public static final class Progress extends Base {
        @Override
        public void update(@NotNull AnActionEvent event) {
            ReviewService service = service(event);
            if (service == null) {
                event.getPresentation().setVisible(false);
                return;
            }
            ReviewService.Progress progress = service.progress();
            event.getPresentation().setVisible(progress.changed() > 0);
            event.getPresentation().setEnabled(false);
            event.getPresentation().setText(progress.shortText());
            event.getPresentation().setDescription(progress.description());
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent event) {
            // Informational toolbar item.
        }
    }
}
