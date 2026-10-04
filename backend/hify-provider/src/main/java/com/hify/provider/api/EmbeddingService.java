package com.hify.provider.api;

import com.hify.common.ExecutionControl;
import java.util.List;

public interface EmbeddingService {
    EmbeddingProfile freeze(String providerId, String model, int dimensions);
    List<float[]> embed(EmbeddingProfile profile, List<String> input, ExecutionControl control);
}
