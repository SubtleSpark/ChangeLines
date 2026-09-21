package dev.subtlespark.changelines;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import org.jetbrains.annotations.NotNull;

public final class ChangeLinesStartup implements StartupActivity.DumbAware {
    @Override public void runActivity(@NotNull Project project) {
        ApplicationManager.getApplication().getService(ChangeLinesInstaller.class).start();
    }
}
