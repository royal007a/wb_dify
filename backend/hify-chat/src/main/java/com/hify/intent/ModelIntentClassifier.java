package com.hify.intent;

import com.hify.runtime.ModelClient;

import java.util.List;

@FunctionalInterface
public interface ModelIntentClassifier {
    List<IntentCandidate> classify(String normalizedInput, ModelClient modelClient, String model);
}
