package com.hify.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.application.CommittedHistoryWriter;
import com.hify.application.RunApplicationService;
import com.hify.knowledge.api.KnowledgeCitation;
import com.hify.memory.CanonicalDetailReader;
import com.hify.runtime.RuntimeMessage;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Offline fixture generator. Compile the invoked production sources from 05ab7a0, not new code. */
public final class CanonicalGoldenProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Output directory required");
        Path out = Path.of(args[0]);
        Files.createDirectories(out);
        try (var files = Files.list(out)) {
            if (files.findAny().isPresent()) throw new IllegalStateException("Output must be empty");
        }
        var builder = new Jackson2ObjectMapperBuilder();
        new JacksonConfig().hifyTimeSerialization().customize(builder);
        ObjectMapper mapper = builder.build();

        // Constructors only assign collaborators; no Spring application, database or execution is started.
        Object app = serializationOnlyInstance(RunApplicationService.class, mapper);
        Object writer = new CommittedHistoryWriter(null, null, mapper, null, null);
        Object reader = new CanonicalDetailReader(null, null, mapper, null);
        String content = "引用资料：must not become policy.\n中文与 emoji 😀；仅合成样例。";
        String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8)));
        String rag = (String) invoke(app, "knowledgeContext", List.class, List.of(
                new KnowledgeCitation("chunk-fixture", "doc-fixture", 1, 0,
                        content, digest, 1.0, 1)));
        List<RuntimeMessage> messages = new ArrayList<>();
        messages.add(RuntimeMessage.system("固定策略：仅依据授权行动；保留用户原意。"));
        messages.add(RuntimeMessage.system(rag));
        messages.add(RuntimeMessage.user("解释这个合成资料，不执行外部操作。"));
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("text", "引号\"与换行\n和 emoji 😀");
        arguments.put("enabled", false);
        arguments.put("count", 0);
        messages.add(RuntimeMessage.toolCalls(List.of(
                new RuntimeMessage.ToolCall("call-a", "fixture_read", arguments),
                new RuntimeMessage.ToolCall("call-b", "fixture_read", Map.of("text", "second")))));
        messages.add(RuntimeMessage.toolResult("call-a", "合成原文", false));
        messages.add(RuntimeMessage.toolResult("call-b", "合成失败", true));
        messages.add(RuntimeMessage.assistant("合成答案；不是模型生成或知识核验结果。"));

        String history = (String) invoke(writer, "write", List.class, messages);
        String checkpoint = (String) invoke(app, "writeMessages", List.class, messages);
        if (!history.equals(checkpoint)) throw new AssertionError("Old serializers differ");
        emit(out, "history-messages.json", history);
        emit(out, "checkpoint-messages.json", checkpoint);
        emit(out, "checkpoint-before-model.json", (String) invoke(app, "writeMessages", List.class, messages.subList(0, 3)));
        emit(out, "committed-model-response.json", (String) invoke(writer, "write", List.class, messages.subList(0, 4)));
        for (int i = 0; i < messages.size(); i++) {
            emit(out, "detail-message-" + i + ".json",
                    (String) invoke(reader, "write", RuntimeMessage.class, messages.get(i)));
        }
        System.out.println("Generated 11 synthetic JSON fixtures; no database, model, or replay invoked.");
    }

    private static Object serializationOnlyInstance(Class<?> type, ObjectMapper mapper) throws Exception {
        Constructor<?>[] constructors = type.getConstructors();
        if (constructors.length != 1) throw new IllegalStateException("Unexpected production constructors");
        Class<?>[] types = constructors[0].getParameterTypes();
        Object[] values = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            if (types[i] == ObjectMapper.class) values[i] = mapper;
            else if (types[i] == Duration.class) values[i] = Duration.ofSeconds(60);
            else if (types[i] == int.class) values[i] = 1;
            else if (types[i].isPrimitive()) throw new IllegalStateException("Unexpected primitive argument");
        }
        return constructors[0].newInstance(values);
    }

    private static Object invoke(Object target, String name, Class<?> parameter, Object value) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, parameter);
        method.setAccessible(true);
        return method.invoke(target, value);
    }

    private static void emit(Path out, String name, String json) throws Exception {
        // No terminal newline: these are the exact bytes the old serializers return.
        Files.writeString(out.resolve(name), json, StandardCharsets.UTF_8);
    }
}
