package dev.subtlespark.changelines;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Exact line-level Myers edit distance, without retaining a quadratic edit trace. */
public final class LineDiff {
    public static final int MAX_CHARS = 2_000_000;
    public static final int MAX_LINES = 100_000;
    private static final long MAX_WORK = 8_000_000;

    private LineDiff() {}

    public record Stats(int added, int removed) {
        public Stats {
            if (added < 0 || removed < 0) throw new IllegalArgumentException("Negative line count");
        }
    }

    public static final class LimitExceededException extends RuntimeException {
        public LimitExceededException() {
            super("Line diff exceeds the configured size or work limit", null, false, false);
        }
    }

    public static Stats calculate(String before, String after, Runnable checkCancelled) {
        Objects.requireNonNull(checkCancelled).run();
        List<String> left = lines(before);
        List<String> right = lines(after);
        int prefix = 0;
        int leftEnd = left.size();
        int rightEnd = right.size();
        while (prefix < leftEnd && prefix < rightEnd && left.get(prefix).equals(right.get(prefix))) {
            if ((prefix & 1023) == 0) checkCancelled.run();
            prefix++;
        }
        while (leftEnd > prefix && rightEnd > prefix && left.get(leftEnd - 1).equals(right.get(rightEnd - 1))) {
            if ((leftEnd & 1023) == 0) checkCancelled.run();
            leftEnd--;
            rightEnd--;
        }
        int n = leftEnd - prefix;
        int m = rightEnd - prefix;
        if (n == 0 || m == 0) return new Stats(m, n);

        int max = n + m;
        int offset = max + 1;
        int[] furthest = new int[2 * max + 3];
        Arrays.fill(furthest, -1);
        furthest[offset + 1] = 0;
        long work = 0;
        for (int distance = 0; distance <= max; distance++) {
            checkCancelled.run();
            for (int diagonal = -distance; diagonal <= distance; diagonal += 2) {
                if (++work > MAX_WORK) throw new LimitExceededException();
                int index = offset + diagonal;
                int x = diagonal == -distance || (diagonal != distance && furthest[index - 1] < furthest[index + 1])
                        ? furthest[index + 1] : furthest[index - 1] + 1;
                int y = x - diagonal;
                while (x < n && y < m && left.get(prefix + x).equals(right.get(prefix + y))) {
                    x++;
                    y++;
                    if (++work > MAX_WORK) throw new LimitExceededException();
                    if ((work & 1023) == 0) checkCancelled.run();
                }
                furthest[index] = x;
                if (x >= n && y >= m) {
                    return new Stats((distance + m - n) / 2, (distance + n - m) / 2);
                }
            }
        }
        throw new IllegalStateException("No edit path found");
    }

    private static List<String> lines(String text) {
        Objects.requireNonNull(text);
        if (text.length() > MAX_CHARS) throw new LimitExceededException();
        // Normalize line separators, but preserve whether the final line has a terminator.
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        List<String> result = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                if (result.size() == MAX_LINES) throw new LimitExceededException();
                result.add(text.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < text.length()) {
            if (result.size() == MAX_LINES) throw new LimitExceededException();
            result.add(text.substring(start));
        }
        return result;
    }
}
