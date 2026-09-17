package dev.subtlespark.changelines;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.CancellationException;

/** Hashes ordered, length-delimited fields; absence and an empty file are different. */
public final class ReviewFingerprint {
    private ReviewFingerprint() {}

    public static String hash(String... fields) {
        final MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        byte[] buffer = new byte[8192];
        for (String field : fields) {
            digest.update((byte) (field == null ? 0 : 1));
            if (field == null) continue;
            int length = field.length();
            for (int shift = 24; shift >= 0; shift -= 8) digest.update((byte) (length >>> shift));
            // Preserve Java characters exactly, including a temporarily incomplete surrogate
            // pair in an unsaved document, rather than replacing it during UTF-8 encoding.
            for (int start = 0; start < length;) {
                if (Thread.currentThread().isInterrupted()) throw new CancellationException();
                int end = Math.min(length, start + buffer.length / 2);
                int used = 0;
                while (start < end) {
                    char c = field.charAt(start++);
                    buffer[used++] = (byte) (c >>> 8);
                    buffer[used++] = (byte) c;
                }
                digest.update(buffer, 0, used);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String content(String before, String after) {
        return hash("ChangeLines.review.content.v1", before, after);
    }
}
