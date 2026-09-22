package dev.subtlespark.changelines;

import com.intellij.testFramework.LightPlatformTestCase;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.util.ui.JBUI;

import java.awt.Font;

public final class PinnedSuffixBudgetTest extends LightPlatformTestCase {
    public void testReviewSummaryLeavesRoomForFilenameContext() {
        SimpleColoredComponent label = suffix();
        int numericWidth = label.computePreferredSize(true).width;
        int available = numericWidth + JBUI.scale(140);
        ViewportPinnedCell.fitSuffix(label, available);
        assertTrue(label.getPreferredSize().width <= available - JBUI.scale(86));
        assertTrue(label.getCharSequence(false).toString().startsWith("+123  -456"));
    }

    public void testExactlyEnoughSpaceForCountsDoesNotTruncateEitherNumber() {
        SimpleColoredComponent label = suffix();
        int numericWidth = label.computePreferredSize(true).width;
        ViewportPinnedCell.fitSuffix(label, numericWidth);
        assertEquals("+123  -456", label.getCharSequence(false).toString());
        assertEquals(numericWidth, label.getPreferredSize().width);
    }

    public void testPhysicallyImpossibleWidthShowsEllipsisNotPartialNumbers() {
        SimpleColoredComponent label = suffix();
        ViewportPinnedCell.fitSuffix(label, label.computePreferredSize(true).width - 1);
        assertEquals("…", label.getCharSequence(false).toString());
    }

    private static SimpleColoredComponent suffix() {
        SimpleColoredComponent component = new SimpleColoredComponent();
        component.setFont(new Font("Dialog", Font.PLAIN, 14));
        component.append("+123", SimpleTextAttributes.REGULAR_ATTRIBUTES, true);
        component.append("  -456", SimpleTextAttributes.REGULAR_ATTRIBUTES, true);
        component.append("  部分统计 20 / 30 · 已审阅 10 / 20 · 需重审 3 · 跳过 10", SimpleTextAttributes.GRAYED_ATTRIBUTES, false);
        return component;
    }
}
