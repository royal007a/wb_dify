package com.hify.knowledge.application;

import java.util.ArrayList;
import java.util.List;

public final class RecursiveTextChunker {
    public record Chunk(int ordinal, String content, int tokenCount) {}

    public List<Chunk> split(String text, int maxTokens, int overlapTokens) {
        String normalized = text == null ? "" : text.replace("\r\n", "\n").trim();
        if (normalized.isBlank()) return List.of();
        int maxChars = maxTokens * 4;
        int overlapChars = overlapTokens * 4;
        List<String> semantic = splitSemantic(normalized, maxChars);
        List<Chunk> result = new ArrayList<>();
        String carry = "";
        for (String part : semantic) {
            String content = carry.isBlank() ? part.strip() : carry + part.strip();
            if (content.isBlank()) continue;
            result.add(new Chunk(result.size(), content, estimateTokens(content)));
            carry = overlapChars == 0 ? "" : content.substring(Math.max(0, content.length() - overlapChars));
        }
        return result;
    }

    private List<String> splitSemantic(String text, int maxChars) {
        List<String> result = new ArrayList<>();
        for (String paragraph : text.split("(?<=\\n\\n)")) {
            if (paragraph.length() <= maxChars) { result.add(paragraph); continue; }
            StringBuilder current = new StringBuilder();
            for (String sentence : paragraph.split("(?<=[。！？.!?\\n])")) {
                if (current.length() + sentence.length() > maxChars && !current.isEmpty()) {
                    result.add(current.toString()); current.setLength(0);
                }
                while (sentence.length() > maxChars) { result.add(sentence.substring(0,maxChars)); sentence=sentence.substring(maxChars); }
                current.append(sentence);
            }
            if (!current.isEmpty()) result.add(current.toString());
        }
        return result;
    }
    private int estimateTokens(String value){ return Math.max(1,(value.length()+3)/4); }
}
