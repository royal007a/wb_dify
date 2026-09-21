package com.hify.mcp.application;
import com.hify.common.*; import org.springframework.stereotype.Component;
@Component public class McpCredentialResolver {
 public String resolve(String ref){if(ref==null||ref.isBlank())return null;String value;if(ref.startsWith("env:"))value=System.getenv(ref.substring(4));else if(ref.startsWith("system:"))value=System.getProperty(ref.substring(7));else throw new BizException(ErrorCode.PARAM_ERROR,"credentialRef only supports env: or system:");if(value==null||value.isBlank())throw new BizException(ErrorCode.PARAM_ERROR,"MCP credential is unavailable");return value;}
}
