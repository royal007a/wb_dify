package com.hify.workflow.application;
import com.hify.common.BizException; import com.hify.common.ErrorCode; import java.util.*;

public class WorkflowExecutionContext {
 private final LinkedHashMap<String,Object> values=new LinkedHashMap<>();
 public WorkflowExecutionContext(String input){this("start",input);}
 public WorkflowExecutionContext(String startNodeKey,String input){values.put(startNodeKey+".userMessage",input);}
 public void set(String nodeKey,String variable,Object value){String key=nodeKey+"."+variable;if(values.containsKey(key))throw new BizException(ErrorCode.CONFLICT,"工作流变量不可覆盖: "+key);values.put(key,value);}
 public void setAll(String nodeKey,Map<String,Object> outputs){
  for(String variable:outputs.keySet())if(values.containsKey(nodeKey+"."+variable))throw new BizException(ErrorCode.CONFLICT,"工作流结构化变量不可覆盖");
  outputs.forEach((variable,value)->values.put(nodeKey+"."+variable,value));
 }
 public Object get(String key){return values.get(key);} public Map<String,Object> snapshot(){return Collections.unmodifiableMap(new LinkedHashMap<>(values));}
 public String resolve(String template){return WorkflowTemplates.resolve(template,values);}
}
