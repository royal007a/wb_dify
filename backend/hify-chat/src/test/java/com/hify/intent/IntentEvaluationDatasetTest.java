package com.hify.intent;

import com.hify.runtime.ModelClient;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IntentEvaluationDatasetTest {
    private static final ModelClient UNUSED_MODEL = request -> {
        throw new AssertionError("Rule-only baseline must not invoke a model through this classifier");
    };

    @Test
    void evaluatesRuleOnlyBaselineAgainstVersionedChineseDataset() throws Exception {
        List<Sample> samples = loadSamples();
        assertThat(samples).hasSizeBetween(100, 200);
        assertThat(samples.stream().map(Sample::id).distinct().count()).isEqualTo(samples.size());
        String tags = samples.stream().map(Sample::tags).reduce("", (left, right) -> left + "," + right);
        assertThat(tags).contains("synonym", "multi_turn", "missing_slot", "boundary",
                "out_of_scope", "typo", "dangerous", "prompt_injection");

        LayeredIntentRouter router = new LayeredIntentRouter(
                new DeterministicIntentRouter(), (input, client, model) -> List.of(), 0.75, 0.10);
        int correctIntent = 0;
        int correctSlots = 0;
        int slotSamples = 0;
        int predictedUnknown = 0;
        int expectedUnknown = 0;
        int trueUnknown = 0;
        int clarifications = 0;
        List<Long> latencies = new ArrayList<>();
        Map<String, Map<String, Integer>> confusion = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> intentConfusion = new LinkedHashMap<>();

        for (Sample sample : samples) {
            long started = System.nanoTime();
            IntentDecision actual = router.route(sample.input(), UNUSED_MODEL, "rule-only");
            latencies.add(System.nanoTime() - started);

            if (sample.expectedIntent().equals(actual.intent())) correctIntent++;
            if (sample.expectedRoute() == IntentRoute.UNKNOWN) expectedUnknown++;
            if (actual.route() == IntentRoute.UNKNOWN) predictedUnknown++;
            if (sample.expectedRoute() == IntentRoute.UNKNOWN && actual.route() == IntentRoute.UNKNOWN) trueUnknown++;
            if (actual.route() == IntentRoute.CLARIFY) clarifications++;
            if (!sample.expectedSlots().isEmpty() || !sample.expectedMissingSlots().isEmpty()) {
                slotSamples++;
                boolean valuesMatch = sample.expectedSlots().entrySet().stream()
                        .allMatch(entry -> entry.getValue().equals(String.valueOf(actual.slots().get(entry.getKey()))));
                if (valuesMatch && sample.expectedMissingSlots().equals(actual.missingSlots())) {
                    correctSlots++;
                }
            }
            confusion.computeIfAbsent(sample.expectedRoute().wireValue(), ignored -> new LinkedHashMap<>())
                    .merge(actual.route().wireValue(), 1, Integer::sum);
            intentConfusion.computeIfAbsent(sample.expectedIntent(), ignored -> new LinkedHashMap<>())
                    .merge(actual.intent(), 1, Integer::sum);
        }

        latencies.sort(Long::compareTo);
        long p95Nanos = latencies.get((int) Math.ceil(latencies.size() * 0.95) - 1);
        double top1 = ratio(correctIntent, samples.size());
        double unknownPrecision = ratio(trueUnknown, predictedUnknown);
        double unknownRecall = ratio(trueUnknown, expectedUnknown);
        double clarifyRate = ratio(clarifications, samples.size());
        double slotAccuracy = ratio(correctSlots, slotSamples);

        System.out.printf("INTENT_BASELINE samples=%d top1=%.4f unknownPrecision=%.4f "
                        + "unknownRecall=%.4f clarifyRate=%.4f slotAccuracy=%.4f p95Ms=%.3f costPerCall=0 "
                        + "routeConfusion=%s intentConfusion=%s%n",
                samples.size(), top1, unknownPrecision, unknownRecall, clarifyRate, slotAccuracy,
                p95Nanos / 1_000_000.0, confusion, intentConfusion);

        assertThat(confusion.keySet()).containsExactlyInAnyOrder("unknown", "clarify", "tool", "workflow");
    }

    private List<Sample> loadSamples() throws Exception {
        var resource = getClass().getResourceAsStream("/intent/intent-eval.zh-CN.tsv");
        assertThat(resource).as("intent evaluation dataset").isNotNull();
        List<Sample> samples = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#") || line.startsWith("id\t")) continue;
                String[] fields = line.split("\\t", -1);
                assertThat(fields).as("dataset row: " + line).hasSize(7);
                samples.add(new Sample(fields[0], fields[1], fields[2],
                        IntentRoute.fromWireValue(fields[3]), parseSlots(fields[4]),
                        parseList(fields[5]), fields[6]));
            }
        }
        return samples;
    }

    private Map<String, String> parseSlots(String value) {
        if (value.isBlank()) return Map.of();
        Map<String, String> slots = new LinkedHashMap<>();
        for (String pair : value.split(";")) {
            String[] parts = pair.split("=", 2);
            if (parts.length != 2) throw new IllegalArgumentException("Invalid expected slot: " + pair);
            slots.put(parts[0], parts[1]);
        }
        return slots;
    }

    private List<String> parseList(String value) {
        return value.isBlank() ? List.of() : List.of(value.split(","));
    }

    private double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }

    private record Sample(String id, String input, String expectedIntent, IntentRoute expectedRoute,
                          Map<String, String> expectedSlots, List<String> expectedMissingSlots, String tags) {}
}
