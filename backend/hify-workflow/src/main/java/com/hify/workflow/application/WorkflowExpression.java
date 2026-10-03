package com.hify.workflow.application;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.BizException;
import com.hify.common.ErrorCode;
import com.hify.workflow.api.WorkflowDefinitionException;
import java.util.List;

/** Parse the DSL once before resolving templates. Substituted text never becomes syntax. */
final class WorkflowExpression {
    private static final ObjectMapper STRINGS=new ObjectMapper(JsonFactory.builder()
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final String left,operator,right;
    private WorkflowExpression(String left,String operator,String right){this.left=left;this.operator=operator;this.right=right;}

    static WorkflowExpression parse(String expression){
        if(expression==null||expression.isBlank())throw invalid();
        String value=expression.trim();
        WorkflowTemplates.references(value);
        char quote=0;int offset=-1;String operator=null;
        for(int i=0;i<value.length();i++){
            char ch=value.charAt(i);
            if(quote!=0){
                if(ch=='\\'){i++;continue;}
                if(ch==quote)quote=0;
                continue;
            }
            if(ch=='\''||ch=='"'){quote=ch;continue;}
            if(value.startsWith("{{",i)){i=value.indexOf("}}",i)+1;continue;}
            String found=null;
            if(value.startsWith("==",i)||value.startsWith("!=",i))found=value.substring(i,i+2);
            else if(value.startsWith("contains",i)&&i>0&&Character.isWhitespace(value.charAt(i-1))
                    &&i+8<value.length()&&Character.isWhitespace(value.charAt(i+8)))found="contains";
            if(found!=null){
                if(operator!=null)throw invalid();
                offset=i;operator=found;i+=found.length()-1;
            }
        }
        if(quote!=0)throw invalid();
        if(operator==null){
            if(!value.equalsIgnoreCase("true")&&!value.equalsIgnoreCase("false")
                    &&!value.matches("\\{\\{\\s*[^{}]+?\\s*}}"))throw invalid();
            return new WorkflowExpression(value,null,null);
        }
        String left=operand(value.substring(0,offset));
        String right=operand(value.substring(offset+operator.length()));
        // Escapes can introduce {{...}} too: validate the decoded templates, not just raw JSON.
        WorkflowTemplates.references(left);WorkflowTemplates.references(right);
        return new WorkflowExpression(left,operator,right);
    }

    List<String> templates(){return operator==null?List.of(left):List.of(left,right);}
    boolean evaluate(WorkflowExecutionContext context){
        String l=context.resolve(left);
        if(operator==null){
            if(l.equalsIgnoreCase("true")||l.equalsIgnoreCase("false"))return Boolean.parseBoolean(l);
            throw new BizException(ErrorCode.CONFLICT,"条件变量必须解析为 true 或 false");
        }
        String r=context.resolve(right);
        return switch(operator){case "contains"->l.contains(r);case "=="->l.equals(r);case "!="->!l.equals(r);default->throw invalid();};
    }
    private static String operand(String input){
        String value=input.trim();if(value.isEmpty())throw invalid();
        if(value.charAt(0)=='\''||value.charAt(0)=='"'){
            try {return STRINGS.readValue(value,String.class);}catch(Exception malformed){throw invalid();}
        }
        // Bare text remains compatible for simple words/phrases and templates. Quote syntax chars.
        if(value.chars().anyMatch(ch->"\"'=!<>&|()".indexOf(ch)>=0))throw invalid();
        return value;
    }
    private static WorkflowDefinitionException invalid(){return new WorkflowDefinitionException(
            "条件表达式须为 true/false、单个布尔模板变量，或两个文本操作数间的 contains、==、!=；含操作符的字面量需加引号");}
}
