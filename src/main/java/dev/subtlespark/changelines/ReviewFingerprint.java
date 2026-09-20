package dev.subtlespark.changelines;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class ReviewFingerprint {
    private ReviewFingerprint() {}

    static String calculate(String vcsRoot,
                            String currentPath,
                            String beforeRevision,
                            String beforeContent,
                            String afterContent) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            add(digest, vcsRoot);
            add(digest, currentPath);
            add(digest, beforeRevision);
            add(digest, beforeContent);
            add(digest, afterContent);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void add(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }
}
