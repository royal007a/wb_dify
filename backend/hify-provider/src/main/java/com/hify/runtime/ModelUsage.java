package com.hify.runtime;

public record ModelUsage(long inputTokens, long outputTokens, long totalTokens) {
    public static ModelUsage of(long inputTokens, long outputTokens, long reportedTotal) {
        long total = reportedTotal > 0 ? reportedTotal : inputTokens + outputTokens;
        return new ModelUsage(Math.max(0, inputTokens), Math.max(0, outputTokens), Math.max(0, total));
    }
}
