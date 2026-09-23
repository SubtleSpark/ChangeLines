package dev.subtlespark.changelines;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;

/** Project-local presentation preference; independent of review marks and folder selection. */
final class FolderSummarySettings {
    static final String KEY = "dev.subtlespark.changelines.showFolderSummary";

    private FolderSummarySettings() {}

    static boolean isVisible(Project project) {
        return !project.isDisposed() && PropertiesComponent.getInstance(project).getBoolean(KEY, false);
    }

    static void setVisible(Project project, boolean visible) {
        if (project.isDisposed() || isVisible(project) == visible) return;
        PropertiesComponent.getInstance(project).setValue(KEY, visible, false);
        // Reuse the existing refresh to invalidate row widths in all open comparison trees.
        ApplicationManager.getApplication().getService(ChangeLinesInstaller.class).requestRefresh();
    }
}
