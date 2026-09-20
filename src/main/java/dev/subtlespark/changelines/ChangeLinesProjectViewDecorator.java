package dev.subtlespark.changelines;

import com.intellij.ide.projectView.PresentationData;
import com.intellij.ide.projectView.ProjectViewNode;
import com.intellij.ide.projectView.ProjectViewNodeDecorator;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.FileStatusManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleTextAttributes;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;

public final class ChangeLinesProjectViewDecorator implements ProjectViewNodeDecorator, DumbAware {
    private static final SimpleTextAttributes ADDED = new SimpleTextAttributes(
            SimpleTextAttributes.STYLE_PLAIN,
            JBColor.namedColor("ChangeLines.added", new JBColor(0x247A38, 0x73B97B)));
    private static final SimpleTextAttributes REMOVED = new SimpleTextAttributes(
            SimpleTextAttributes.STYLE_PLAIN,
            JBColor.namedColor("ChangeLines.removed", new JBColor(0xC62828, 0xE88989)));
    private static final SimpleTextAttributes REVIEWED = new SimpleTextAttributes(
            SimpleTextAttributes.STYLE_PLAIN,
            JBColor.namedColor("ChangeLines.reviewed", new JBColor(0x247A38, 0x73B97B)));
    private static final SimpleTextAttributes NEEDS_REVIEW = new SimpleTextAttributes(
            SimpleTextAttributes.STYLE_BOLD,
            JBColor.namedColor("ChangeLines.needsReview", new JBColor(0xA86400, 0xE0A040)));

    @Override
    public void decorate(@NotNull ProjectViewNode<?> node, @NotNull PresentationData data) {
        Project project = node.getProject();
        VirtualFile file = node.getVirtualFile();
        if (project == null || file == null || file.isDirectory() || !file.isValid()) return;

        ReviewService.Decoration decoration = project.getService(ReviewService.class).decoration(file);
        if (decoration == null) return;

        String name = data.getPresentableText();
        if (name == null) return;

        // Standard Project View file nodes normally only set presentableText. If another decorator
        // already supplied colored fragments, preserve them and append our suffix.
        if (data.getColoredText().isEmpty()) {
            Color fileColor = FileStatusManager.getInstance(project).getStatus(file).getColor();
            data.addText(name, new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, fileColor));
        }

        if (decoration.stats() != null) {
            data.addText("  +" + decoration.stats().added(), ADDED);
            data.addText("  -" + decoration.stats().removed(), REMOVED);
        } else if (decoration.loading()) {
            data.addText("  …", SimpleTextAttributes.GRAYED_ATTRIBUTES);
        }

        if (!decoration.loading()) {
            switch (decoration.status()) {
                case UNREVIEWED -> data.addText("  ○", SimpleTextAttributes.GRAYED_ATTRIBUTES);
                case REVIEWED -> data.addText("  ✓", REVIEWED);
                case NEEDS_REVIEW -> data.addText("  !", NEEDS_REVIEW);
                case UNREVIEWABLE -> data.addText("  ⊘", SimpleTextAttributes.GRAYED_ATTRIBUTES);
            }
        }

        String reviewTooltip = "ChangeLines: " + decoration.detail();
        String oldTooltip = data.getTooltip();
        data.setTooltip(oldTooltip == null || oldTooltip.isBlank()
                ? reviewTooltip
                : oldTooltip + " · " + reviewTooltip);
    }
}
