package dev.subtlespark.changelines;

import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserChangeNode;
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode;
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNodeRenderer;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.testFramework.LightPlatformTestCase;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.vcsUtil.VcsUtil;

import javax.swing.JTree;
import javax.swing.tree.DefaultTreeModel;
import java.awt.Color;

/** Regression tests for native VCS palette, live scheme changes, and selection behavior. */
public final class ChangeLinesColorsTest extends LightPlatformTestCase {
    public void testUsesNativeVcsColorsWithoutChangingFileNameOrFont() {
        Fixture fixture = fixture(false);
        var original = fixture.nativeRenderer();
        original.getTreeCellRendererComponent(fixture.tree(), fixture.node(), false, false, true, 0, false);
        var fileNameAttributes = attributes(original, "Colors.txt");
        var font = original.getFont();
        var icon = original.getIcon();

        var rendered = fixture.render(false);
        assertSame("The native label must be reused", original, rendered);
        assertEquals(fileNameAttributes, attributes(rendered, "Colors.txt"));
        assertEquals(font, rendered.getFont());
        assertEquals(icon, rendered.getIcon());
        assertEquals(FileStatus.ADDED.getColor(), attributes(rendered, "  +2").getFgColor());
        assertEquals(FileStatus.DELETED.getColor(), attributes(rendered, "  -1").getFgColor());
        assertEquals(SimpleTextAttributes.STYLE_PLAIN, attributes(rendered, "  +2").getStyle());
        assertEquals(SimpleTextAttributes.STYLE_PLAIN, attributes(rendered, "  -1").getStyle());
    }

    public void testExistingRendererFollowsChangedVcsSchemeColors() {
        EditorColorsManager manager = EditorColorsManager.getInstance();
        EditorColorsScheme original = manager.getGlobalScheme();
        EditorColorsScheme temporary = (EditorColorsScheme) original.clone();
        temporary.setName("ChangeLines color regression test");
        // Deliberately unusual test colors prove that the plugin does not force green/red.
        Color firstAdded = new Color(33, 66, 99);
        Color firstDeleted = new Color(111, 77, 44);
        Color nextAdded = new Color(180, 170, 40);
        Color nextDeleted = new Color(70, 160, 180);
        Fixture fixture = fixture(false);
        try {
            temporary.setColor(FileStatus.ADDED.getColorKey(), firstAdded);
            temporary.setColor(FileStatus.DELETED.getColorKey(), firstDeleted);
            manager.setGlobalScheme(temporary);
            assertEquals(firstAdded, FileStatus.ADDED.getColor());
            assertEquals(firstDeleted, FileStatus.DELETED.getColor());
            var first = fixture.render(false);
            assertEquals(firstAdded, attributes(first, "  +2").getFgColor());
            assertEquals(firstDeleted, attributes(first, "  -1").getFgColor());

            temporary.setColor(FileStatus.ADDED.getColorKey(), nextAdded);
            temporary.setColor(FileStatus.DELETED.getColorKey(), nextDeleted);
            var next = fixture.render(false);
            assertEquals(nextAdded, attributes(next, "  +2").getFgColor());
            assertEquals(nextDeleted, attributes(next, "  -1").getFgColor());
        } finally {
            manager.setGlobalScheme(original);
        }
    }

    public void testSelectedRowsKeepNativeFocusedAndUnfocusedColorRules() {
        for (boolean focused : new boolean[]{false, true}) {
            Fixture fixture = fixture(focused);
            var original = fixture.nativeRenderer();
            original.getTreeCellRendererComponent(fixture.tree(), fixture.node(), true, false, true, 0, focused);
            var fileNameAttributes = attributes(original, "Colors.txt");
            // Ask the native renderer to apply its own selection policy to the same semantic colors.
            original.append("native-added", new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, FileStatus.ADDED.getColor()));
            original.append("native-deleted", new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, FileStatus.DELETED.getColor()));
            var expectedAdded = attributes(original, "native-added");
            var expectedDeleted = attributes(original, "native-deleted");

            var rendered = fixture.render(true);
            assertEquals(fileNameAttributes, attributes(rendered, "Colors.txt"));
            assertEquals(expectedAdded, attributes(rendered, "  +2"));
            assertEquals(expectedDeleted, attributes(rendered, "  -1"));
        }
    }

    private Fixture fixture(boolean focused) {
        FilePath path = VcsUtil.getFilePath("/changelines-test/" + getTestName(true) + "/Colors.txt", false);
        ContentRevision revision = new ContentRevision() {
            @Override public String getContent() { return "text\n"; }
            @Override public FilePath getFile() { return path; }
            @Override public VcsRevisionNumber getRevisionNumber() { return VcsRevisionNumber.NULL; }
        };
        var node = new ChangesBrowserChangeNode(getProject(), new Change(revision, revision), null);
        var root = ChangesBrowserNode.createRoot();
        root.add(node);
        var tree = new JTree(new DefaultTreeModel(root)) {
            @Override public boolean hasFocus() { return focused; }
        };
        var original = new ChangesBrowserNodeRenderer(getProject(), () -> false, false);
        var wrapper = new ChangeLinesRenderer(original, (change, visible) ->
                new LineStatsService.Result(LineStatsService.State.READY, new LineDiff.Stats(2, 1)));
        return new Fixture(tree, node, original, wrapper);
    }

    private static SimpleTextAttributes attributes(SimpleColoredComponent label, String text) {
        var fragments = label.iterator();
        while (fragments.hasNext()) {
            if (text.equals(fragments.next())) return fragments.getTextAttributes();
        }
        throw new AssertionError("Missing fragment " + text + " in " + label.getCharSequence(false));
    }

    private record Fixture(JTree tree, ChangesBrowserChangeNode node,
            ChangesBrowserNodeRenderer nativeRenderer, ChangeLinesRenderer wrapper) {
        SimpleColoredComponent render(boolean selected) {
            return (SimpleColoredComponent) wrapper.getTreeCellRendererComponent(
                    tree, node, selected, false, true, 0, tree.hasFocus());
        }
    }
}
