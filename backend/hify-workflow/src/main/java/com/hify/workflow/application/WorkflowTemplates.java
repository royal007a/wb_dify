package com.hify.workflow.application;

import com.hify.common.BizException;
import com.hify.common.ErrorCode;
import java.util.*;
import java.util.regex.*;

/** Shared syntax, single-pass interpolation. Substituted values are never templates. */
final class WorkflowTemplates {
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*([^{}]+?)\\s*}}");
    private static final Pattern IDENTIFIER = Pattern.compile("[\\p{L}\\p{N}_-]{1,128}");
    static boolean identifier(String value) { return value != null && IDENTIFIER.matcher(value).matches(); }

    static Set<String> references(String template) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = VARIABLE.matcher(template);
        int previous = 0;
        while (matcher.find()) {
            checkLiteral(template.substring(previous, matcher.start()));
            String key = matcher.group(1).trim();
            String[] parts = key.split("\\.", -1);
            if (parts.length != 2 || !identifier(parts[0]) || !identifier(parts[1]))
                throw new BizException(ErrorCode.PARAM_ERROR, "模板变量必须是 nodeKey.variable");
            result.add(key);
            previous = matcher.end();
        }
        checkLiteral(template.substring(previous));
        return result;
    }
    static String resolve(String template, Map<String, Object> values) {
        if (template == null) return "";
        for (String key : references(template)) {
            if (!values.containsKey(key) || values.get(key) == null)
                throw new BizException(ErrorCode.CONFLICT, "工作流缺少变量: " + key);
        }
        Matcher matcher = VARIABLE.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) matcher.appendReplacement(result, Matcher.quoteReplacement(String.valueOf(values.get(matcher.group(1).trim()))));
        matcher.appendTail(result);
        return result.toString();
    }
    private static void checkLiteral(String literal) {
        if (literal.contains("{{") || literal.contains("}}")) throw new BizException(ErrorCode.PARAM_ERROR, "模板占位符未闭合或格式无效");
    }
}
