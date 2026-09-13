package com.hify.runtime;

public interface ModelClient {
    RuntimeMessage generate(ModelRequest request);

    default RuntimeMessage generateStream(ModelRequest request, ModelStreamObserver observer) {
        RuntimeMessage result = generate(request);
        if (result.content() != null && !result.content().isEmpty()) observer.onTextDelta(result.content());
        return result;
    }
}
