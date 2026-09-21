package dev.subtlespark.changelines;

import java.util.ArrayList;
import java.util.List;

/** A cached summary of actual descendant Changes, never a filesystem directory scan. */
record FolderSummary(long added, long removed, int files, int counted,
                     int reviewed, int checking, int stale, int skipped) {
    int reviewable() { return files - skipped; }

    String suffix() {
        List<String> parts = new ArrayList<>();
        if (checking > 0) parts.add("统计中 " + counted + " / " + files);
        else if (counted < files) parts.add(counted == 0 ? "统计不可用" : "部分统计 " + counted + " / " + files);
        if (reviewable() > 0) {
            parts.add(reviewed == reviewable() && checking == 0
                    ? "已审阅" : "已审阅 " + reviewed + " / " + reviewable());
        }
        if (stale > 0) parts.add("需重审 " + stale);
        if (skipped > 0) parts.add("跳过 " + skipped);
        return String.join(" · ", parts);
    }

    static final class Builder {
        private long added, removed;
        private int files, counted, reviewed, checking, stale, skipped;

        void add(LineStatsService.Result result, ReviewSession.Status status) {
            files++;
            if (result.stats() != null) {
                added += result.stats().added();
                removed += result.stats().removed();
                counted++;
            }
            if (result.state() == LineStatsService.State.LOADING) checking++;
            if (status == ReviewSession.Status.REVIEWED) reviewed++;
            else if (status == ReviewSession.Status.STALE) stale++;
            else if (status == ReviewSession.Status.UNAVAILABLE) skipped++;
        }

        FolderSummary build() {
            return new FolderSummary(added, removed, files, counted, reviewed, checking, stale, skipped);
        }
    }
}
