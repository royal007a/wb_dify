package com.hify.workflow.application;
import com.hify.common.BizException; import com.hify.common.ErrorCode; import java.util.*;

public class WorkflowExecutionContext {
 private final LinkedHashMap<String,Object> values=new LinkedHashMap<>();
 public WorkflowExecutionContext(String input){values.put("start.userMessage",input);}
 public void set(String nodeKey,String variable,Object value){String key=nodeKey+"."+variable;if(values.containsKey(key))throw new BizException(ErrorCode.CONFLICT,"工作流变量不可覆盖: "+key);values.put(key,value);}
 public Object get(String key){return values.get(key);} public Map<String,Object> snapshot(){return Collections.unmodifiableMap(new LinkedHashMap<>(values));}
 public String resolve(String template){String out=template==null?"":template;for(var item:values.entrySet())out=out.replace("{{"+item.getKey()+"}}",String.valueOf(item.getValue()));return out;}
}
