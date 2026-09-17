package dev.subtlespark.changelines;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

/** Also runnable with plain javac/java, without downloading an IDE or test framework. */
public final class LineDiffSelfTest {
    private static final Runnable NO_CANCEL = () -> {};
    private static int cases;

    public static void main(String[] args) {
        cases = 0;
        check("", "", 0, 0);
        check("", "a\n", 1, 0);
        check("", "a", 1, 0);
        check("a\n", "", 0, 1);
        check("a\n", "b\n", 1, 1);
        check("a\nb\n", "a\nx\nb\n", 1, 0);
        check("a\nx\nb\n", "a\nb\n", 0, 1);
        check("a\nb\n", "a\nb\n", 0, 0);
        check("a\r\nb\r\n", "a\nb\n", 0, 0);
        check("a\rb\r", "a\nb\n", 0, 0);
        check("a", "a\n", 1, 1);
        check("a\n", "a", 1, 1);
        check("", "\n\n", 2, 0);
        check("a\n\n", "a\n", 0, 1);
        check("a\n b\n", "a\nb\n", 1, 1);
        check("a\nb\na\n", "a\na\nb\n", 1, 1);
        check("中文\n猫\n", "中文\n犬\n", 1, 1);
        String prefix = "unchanged\n".repeat(20_000);
        check(prefix + "old\n" + prefix, prefix + "new\n" + prefix, 1, 1);

        // Independent O(n*m) LCS oracle, including duplicate lines and missing EOF newline.
        Random random = new Random(731_261);
        for (int i = 0; i < 5_000; i++) {
            String before = randomText(random);
            String after = randomText(random);
            List<String> left = tokens(before);
            List<String> right = tokens(after);
            int lcs = lcs(left, right);
            check(before, after, right.size() - lcs, left.size() - lcs);
        }

        expect(LineDiff.LimitExceededException.class,
                () -> LineDiff.calculate("x".repeat(LineDiff.MAX_CHARS + 1), "", NO_CANCEL));
        expect(LineDiff.LimitExceededException.class,
                () -> LineDiff.calculate("\n".repeat(LineDiff.MAX_LINES + 1), "", NO_CANCEL));
        expect(LineDiff.LimitExceededException.class,
                () -> LineDiff.calculate("left\n".repeat(5_000), "right\n".repeat(5_000), NO_CANCEL));
        expect(CancellationException.class,
                () -> LineDiff.calculate("a", "b", () -> { throw new CancellationException(); }));
        AtomicInteger polls = new AtomicInteger();
        expect(CancellationException.class, () -> LineDiff.calculate("a\n".repeat(2_000), "b\n".repeat(2_000),
                () -> { if (polls.incrementAndGet() == 10) throw new CancellationException(); }));
        System.out.println("Passed " + cases + " line-diff checks (including 5,000 randomized LCS comparisons).");
    }

    private static void check(String before, String after, int added, int removed) {
        LineDiff.Stats actual = LineDiff.calculate(before, after, NO_CANCEL);
        LineDiff.Stats expected = new LineDiff.Stats(added, removed);
        if (!actual.equals(expected)) throw new AssertionError("Expected " + expected + ", got " + actual);
        cases++;
    }

    private static void expect(Class<? extends RuntimeException> type, Runnable action) {
        try { action.run(); }
        catch (RuntimeException failure) {
            if (!type.isInstance(failure)) throw failure;
            cases++;
            return;
        }
        throw new AssertionError("Expected " + type.getName());
    }

    private static String randomText(Random random) {
        StringBuilder result = new StringBuilder();
        int count = random.nextInt(18);
        for (int i = 0; i < count; i++) {
            result.append((char) ('a' + random.nextInt(5)));
            if (i < count - 1 || random.nextBoolean()) result.append('\n');
        }
        return result.toString();
    }

    private static List<String> tokens(String text) {
        List<String> result = new ArrayList<>(List.of(text.split("(?<=\n)", -1)));
        if (!result.isEmpty() && result.getLast().isEmpty()) result.removeLast();
        return result;
    }

    private static int lcs(List<String> left, List<String> right) {
        int[][] lengths = new int[left.size() + 1][right.size() + 1];
        for (int i = 1; i <= left.size(); i++) {
            for (int j = 1; j <= right.size(); j++) {
                lengths[i][j] = left.get(i - 1).equals(right.get(j - 1)) ? lengths[i - 1][j - 1] + 1
                        : Math.max(lengths[i - 1][j], lengths[i][j - 1]);
            }
        }
        return lengths[left.size()][right.size()];
    }
}
