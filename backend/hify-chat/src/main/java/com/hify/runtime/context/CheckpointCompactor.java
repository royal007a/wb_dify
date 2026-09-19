package com.hify.runtime.context;

import com.hify.runtime.RuntimeMessage;
import java.util.List;

@FunctionalInterface
public interface CheckpointCompactor {
    List<RuntimeMessage> compact(List<RuntimeMessage> messages, int targetTokens);
}
