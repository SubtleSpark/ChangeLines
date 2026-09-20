package dev.subtlespark.changelines;

public enum ReviewStatus {
    UNREVIEWED("未审阅", "○"),
    REVIEWED("已审阅", "✓"),
    NEEDS_REVIEW("需重审", "!"),
    UNREVIEWABLE("不可审阅", "⊘");

    private final String label;
    private final String glyph;

    ReviewStatus(String label, String glyph) {
        this.label = label;
        this.glyph = glyph;
    }

    public String label() {
        return label;
    }

    public String glyph() {
        return glyph;
    }
}
