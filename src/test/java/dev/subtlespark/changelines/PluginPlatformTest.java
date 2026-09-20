package dev.subtlespark.changelines;

import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.testFramework.LightPlatformTestCase;

public final class PluginPlatformTest extends LightPlatformTestCase {
    public void testPluginServicesAndActionsLoad() {
        var plugin = PluginManagerCore.getPlugin(PluginId.getId("dev.subtlespark.changelines"));
        assertNotNull(plugin);
        assertTrue(plugin.isEnabled());

        assertNotNull(getProject().getService(ReviewStore.class));
        assertNotNull(getProject().getService(ReviewService.class));

        ActionManager actions = ActionManager.getInstance();
        assertNotNull(actions.getAction("ChangeLines.MarkReviewed"));
        assertNotNull(actions.getAction("ChangeLines.UnmarkReviewed"));
        assertNotNull(actions.getAction("ChangeLines.MarkAndNext"));
        assertNotNull(actions.getAction("ChangeLines.NextUnreviewed"));
        assertNotNull(actions.getAction("ChangeLines.ResetReviews"));
        assertNotNull(actions.getAction("ChangeLines.Progress"));
    }
}
