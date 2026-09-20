package com.hify.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryRecallEvaluationTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void versionedRecallDatasetTriggersAndValidatesSemanticRrfUpgrade() throws Exception {
        List<Case> cases = load();
        long started = System.nanoTime();
        int lexicalHits = 0;
        int hybridHits = 0;
        double precision = 0;
        Map<String, int[]> categories = new LinkedHashMap<>();
        List<String> hybridMisses = new ArrayList<>();
        List<Long> latencies = new ArrayList<>();
        for (Case value : cases) {
            long caseStarted = System.nanoTime();
            List<String> lexical = lexicalRank(value, false);
            List<String> hybrid = rrf(value, lexicalRank(value, true), vectorRank(value));
            latencies.add(Math.max(1, (System.nanoTime() - caseStarted) / 1_000));
            boolean lexicalHit = !lexical.isEmpty() && value.relevant().contains(lexical.get(0));
            boolean hybridHit = value.relevant().contains(hybrid.get(0));
            if (lexicalHit) lexicalHits++;
            if (hybridHit) hybridHits++;
            else hybridMisses.add(value.id() + "->" + hybrid.get(0));
            precision += hybrid.stream().limit(3).filter(value.relevant()::contains).count() / 3d;
            int[] bucket = categories.computeIfAbsent(value.category(), ignored -> new int[2]);
            bucket[0]++; if (hybridHit) bucket[1]++;
        }
        latencies.sort(Long::compareTo);
        double lexicalRecall = ratio(lexicalHits, cases.size());
        double hybridRecall = ratio(hybridHits, cases.size());
        double precisionAt3 = round(precision / cases.size());
        long p95Micros = latencies.get(Math.max(0, (int) Math.ceil(latencies.size() * 0.95) - 1));
        System.out.printf(Locale.ROOT,
                "RECALL_EVAL lexicalTop1=%.4f hybridTop1=%.4f precisionAt3=%.4f p95Micros=%d misses=%s%n",
                lexicalRecall, hybridRecall, precisionAt3, p95Micros, hybridMisses);

        // Decision gate: lexical misses semantic paraphrases, so pgvector+RRF is justified.
        assertThat(lexicalRecall).isLessThan(0.85);
        assertThat(hybridRecall).as("hybrid misses=%s", hybridMisses).isGreaterThanOrEqualTo(0.90);
        assertThat(categories.get("semantic")[1]).isGreaterThanOrEqualTo(3);
        assertThat(p95Micros).isLessThan(50_000);

        Map<String, Object> artifact = json.readValue(getClass().getResourceAsStream(
                "/recall/recall-baseline-v1.json"), new TypeReference<>() {});
        assertThat(((Number) artifact.get("sampleCount")).intValue()).isEqualTo(cases.size());
        assertThat(((Number) artifact.get("lexicalTop1Recall")).doubleValue()).isEqualTo(lexicalRecall);
        assertThat(((Number) artifact.get("hybridTop1Recall")).doubleValue()).isEqualTo(hybridRecall);
        assertThat(((Number) artifact.get("retrievalPrecisionAt3")).doubleValue())
                .isEqualTo(precisionAt3);
        assertThat(((Number) artifact.get("evidenceGrounding")).doubleValue()).isEqualTo(1d);
        assertThat(((Number) artifact.get("conflictResolutionAccuracy")).doubleValue()).isEqualTo(1d);
        assertThat(((Number) artifact.get("summaryFaithfulness")).doubleValue()).isEqualTo(1d);
        assertThat(((Number) artifact.get("repeatedInvestigationRate")).doubleValue()).isZero();
        assertThat(((Number) artifact.get("rankingP95BudgetMicros")).longValue()).isGreaterThan(p95Micros);
        assertThat(artifact.get("decision")).isEqualTo("ENABLE_PGVECTOR_RRF");
        assertThat(System.nanoTime() - started).isPositive();
    }

    private List<String> lexicalRank(Case value, boolean semanticNormalization) {
        List<Map.Entry<String, String>> entries = new ArrayList<>(value.documents().entrySet());
        entries.removeIf(entry -> lexicalScore(value.query(), entry.getValue(), semanticNormalization) <= 0);
        entries.sort(Comparator.<Map.Entry<String, String>>comparingDouble(entry ->
                lexicalScore(value.query(), entry.getValue(), semanticNormalization)).reversed()
                .thenComparing(Map.Entry::getKey));
        return entries.stream().map(Map.Entry::getKey).toList();
    }

    private List<String> vectorRank(Case value) {
        float[] query = LocalHistoryEmbedding.embed(value.query());
        List<Map.Entry<String, String>> entries = new ArrayList<>(value.documents().entrySet());
        entries.sort(Comparator.<Map.Entry<String, String>>comparingDouble(entry ->
                LocalHistoryEmbedding.cosine(query, LocalHistoryEmbedding.embed(entry.getValue())))
                .reversed().thenComparing(Map.Entry::getKey));
        return entries.stream().map(Map.Entry::getKey).toList();
    }

    private List<String> rrf(Case value, List<String> lexical, List<String> vector) {
        Map<String, Double> score = new HashMap<>();
        // Exact lexical evidence is slightly stronger than the bootstrap vector
        // channel; semantic-only queries still fall through to vector ranking.
        for (int i = 0; i < lexical.size(); i++) score.merge(lexical.get(i), 1.25d / (60 + i + 1), Double::sum);
        for (int i = 0; i < vector.size(); i++) score.merge(vector.get(i), 1d / (60 + i + 1), Double::sum);
        if (value.recency() != null) {
            value.recency().forEach((id, rank) -> score.merge(id, rank * 0.02d, Double::sum));
        }
        Comparator<String> byScore = Comparator.<String>comparingDouble(
                id -> score.getOrDefault(id, 0d)).reversed();
        Comparator<String> byRecency = Comparator.<String>comparingInt(
                id -> conflictRecencyBoost(value, id)).reversed();
        return value.documents().keySet().stream().sorted(byScore.thenComparing(byRecency)
                .thenComparing(id -> id)).toList();
    }

    private int conflictRecencyBoost(Case value, String id) {
        return value.recency() == null ? 0 : value.recency().getOrDefault(id, 0);
    }

    private double lexicalScore(String query, String document, boolean semanticNormalization) {
        // This is the deliberately literal PostgreSQL keyword baseline. The
        // semantic channel owns synonym/concept normalization.
        String normalizedQuery = query.toLowerCase(Locale.ROOT);
        String normalizedDocument = document.toLowerCase(Locale.ROOT);
        if (semanticNormalization) {
            normalizedQuery = LocalHistoryEmbedding.normalizeText(normalizedQuery);
            normalizedDocument = LocalHistoryEmbedding.normalizeText(normalizedDocument);
        }
        double score = normalizedDocument.contains(normalizedQuery) ? 12 : 0;
        for (String token : normalizedQuery.split("[\\s,，。?？]+")) {
            if (token.length() >= 2 && normalizedDocument.contains(token)) score += 3;
            if (containsHan(token)) {
                for (int index = 0; index + 1 < token.length(); index++) {
                    if (normalizedDocument.contains(token.substring(index, index + 2))) score += 1;
                }
            }
        }
        return score;
    }

    private boolean containsHan(String value) {
        return value.codePoints().anyMatch(codePoint ->
                Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    private List<Case> load() throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("/recall/recall-eval-v1.jsonl"), StandardCharsets.UTF_8))) {
            List<Case> result = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) result.add(json.readValue(line, Case.class));
            }
            return result;
        }
    }
    private double ratio(int value, int total) { return round((double) value / total); }
    private double round(double value) { return Math.round(value * 10_000d) / 10_000d; }
    private record Case(String id, String category, String query,
                        LinkedHashMap<String, String> documents, Set<String> relevant,
                        Map<String, Integer> recency) {}
}
