package com.hify.runtime;

import java.util.List;

@FunctionalInterface
public interface HistoryCommitter {
    HistoryCommitter NOOP = (operationId, messages) ->
            new CommitReceipt(0, "noop", false);

    CommitReceipt commit(String operationId, List<RuntimeMessage> messages);

    record CommitReceipt(long revision, String semanticDigest, boolean replayed) {}
}
