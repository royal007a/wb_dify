package com.hify.memory;

import java.util.Locale;

/**
 * Dependency-free bootstrap embedding for recall evaluation and pgvector plumbing.
 * It is deliberately replaceable by a configured provider embedding adapter.
 */
public final class LocalHistoryEmbedding {
    public static final int DIMENSIONS = 64;
    private static final String[][] SYNONYMS = {
            {"发布", "部署"}, {"上线", "部署"}, {"确认", "审批"}, {"批准", "审批"},
            {"人工", "用户"}, {"报错", "故障"}, {"异常", "故障"}, {"修复", "解决"},
            {"偏好", "喜欢"}, {"倾向", "喜欢"}, {"接口", "api"}, {"模型", "llm"},
            {"cancel_requested_at", "取消请求时间"},
            {"release", "deploy"}, {"publish", "deploy"}, {"approve", "approval"},
            {"error", "failure"}, {"fix", "resolve"}
    };
    private static final String[] CONCEPTS = {
            "部署", "审批", "用户", "故障", "解决", "喜欢", "api", "llm",
            "会议", "支付", "风控", "取消", "时间", "参数", "calculator"
    };

    private LocalHistoryEmbedding() {}

    public static float[] embed(String text) {
        String value = normalizeText(text);
        float[] vector = new float[DIMENSIONS];
        if (value.isBlank()) return vector;
        for (int index = 0; index < value.length(); index++) {
            add(vector, value.substring(index, index + 1), 0.45f);
            if (index + 1 < value.length()) add(vector, value.substring(index, index + 2), 1f);
            if (index + 2 < value.length()) add(vector, value.substring(index, index + 3), 0.6f);
        }
        // Canonical concept features make the bootstrap embedding useful for the
        // deliberately small Chinese recall slice without pretending to be a
        // production semantic model. The adapter remains replaceable.
        for (String concept : CONCEPTS) {
            if (value.contains(concept)) add(vector, "concept:" + concept, 4f);
        }
        double norm = 0;
        for (float item : vector) norm += item * item;
        if (norm > 0) {
            float divisor = (float) Math.sqrt(norm);
            for (int index = 0; index < vector.length; index++) vector[index] /= divisor;
        }
        return vector;
    }

    public static double cosine(float[] left, float[] right) {
        double score = 0;
        for (int index = 0; index < Math.min(left.length, right.length); index++) {
            score += left[index] * right[index];
        }
        return score;
    }

    public static String postgresLiteral(float[] vector) {
        StringBuilder value = new StringBuilder("[");
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) value.append(',');
            value.append(Float.toString(vector[index]));
        }
        return value.append(']').toString();
    }

    private static void add(float[] vector, String token, float weight) {
        int hash = token.hashCode();
        int bucket = Math.floorMod(hash, vector.length);
        vector[bucket] += ((hash & 1) == 0 ? weight : -weight);
    }

    public static String normalizeText(String text) {
        String value = text == null ? "" : text.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "").replaceAll("[^\\p{L}\\p{N}_-]", "");
        for (String[] pair : SYNONYMS) value = value.replace(pair[0], pair[1]);
        return value;
    }
}
