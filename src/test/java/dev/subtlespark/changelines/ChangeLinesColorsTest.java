package dev.subtlespark.changelines;

import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserChangeNode;
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode;
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNodeRenderer;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.testFramework.LightPlatformTestCase;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.vcsUtil.VcsUtil;

import javax.swing.JTree;
import javax.swing.UIManager;
import javax.swing.tree.DefaultTreeModel;
import java.awt.Color;

/** Regression tests for the original red/green palette and native selection behavior. */
public final class ChangeLinesColorsTest extends LightPlatformTestCase {
    public void testOriginalPalettePreservesFileNameFontAndIcon() {
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
        assertOriginalPalette(rendered);
        assertEquals(SimpleTextAttributes.STYLE_PLAIN, attributes(rendered, "  +2").getStyle());
        assertEquals(SimpleTextAttributes.STYLE_PLAIN, attributes(rendered, "  -1").getStyle());
    }

    public void testVcsFileStatusColorsDoNotReplaceOriginalPalette() {
        String addedKey = "VersionControl.FileStatus.ADDED";
        String deletedKey = "VersionControl.FileStatus.DELETED";
        Color firstAdded = new Color(33, 66, 99);
        Color firstDeleted = new Color(111, 77, 44);
        Fixture fixture = fixture(false);
        Object previousAdded = UIManager.put(addedKey, firstAdded);
        Object previousDeleted = UIManager.put(deletedKey, firstDeleted);
        try {
            assertEquals(firstAdded, FileStatus.ADDED.getColor());
            assertEquals(firstDeleted, FileStatus.DELETED.getColor());
            assertOriginalPalette(fixture.render(false));

            // A gray native Deleted color must not turn the minus count gray again.
            UIManager.put(addedKey, new Color(180, 170, 40));
            UIManager.put(deletedKey, new Color(128, 128, 128));
            assertOriginalPalette(fixture.render(false));
        } finally {
            UIManager.put(addedKey, previousAdded);
            UIManager.put(deletedKey, previousDeleted);
        }
    }

    public void testSelectedRowsKeepNativeFocusedAndUnfocusedColorRules() {
        for (boolean focused : new boolean[]{false, true}) {
            Fixture fixture = fixture(focused);
            var original = fixture.nativeRenderer();
            original.getTreeCellRendererComponent(fixture.tree(), fixture.node(), true, false, true, 0, focused);
            var fileNameAttributes = attributes(original, "Colors.txt");
            // The native renderer remains responsible for selection contrast.
            original.append("native-added", new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, addedColor()));
            original.append("native-deleted", new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, removedColor()));
            var expectedAdded = attributes(original, "native-added");
            var expectedDeleted = attributes(original, "native-deleted");

            var rendered = fixture.render(true);
            assertEquals(fileNameAttributes, attributes(rendered, "Colors.txt"));
            assertEquals(expectedAdded, attributes(rendered, "  +2"));
            assertEquals(expectedDeleted, attributes(rendered, "  -1"));
        }
    }

    private static Color addedColor() { return new JBColor(0x247A38, 0x73B97B); }
    private static Color removedColor() { return new JBColor(0xC62828, 0xE88989); }

    private static void assertOriginalPalette(SimpleColoredComponent rendered) {
        assertEquals(addedColor().getRGB(), attributes(rendered, "  +2").getFgColor().getRGB());
        assertEquals(removedColor().getRGB(), attributes(rendered, "  -1").getFgColor().getRGB());
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
