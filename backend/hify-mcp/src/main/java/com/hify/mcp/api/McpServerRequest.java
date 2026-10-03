package com.hify.mcp.api;
import jakarta.validation.constraints.*;
import com.fasterxml.jackson.annotation.JsonProperty;
public record McpServerRequest(
 @NotBlank @Size(max=160) String name,
 @NotBlank @Size(max=1000) String endpointUrl,
 @Size(max=255)
 @Pattern(regexp="\\s*(?:env:[A-Za-z_][A-Za-z0-9_]*|system:[A-Za-z_][A-Za-z0-9_.-]*)?\\s*",
          message="凭证引用只支持 env:变量名 或 system:属性名；无鉴权可留空，请勿填写 Token 明文")
 String credentialRef,
 boolean enabled,
 @Pattern(regexp="KEEP|TOKEN|REFERENCE|CLEAR", message="未知的凭据操作") String credentialAction,
 @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) @Size(max=8192) String credentialToken
){
 @Override public String toString(){return "McpServerRequest[credentials=REDACTED]";}
}
