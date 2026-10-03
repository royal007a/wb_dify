package com.hify.mcp.api;
import jakarta.validation.constraints.*;
public record McpServerRequest(
 @NotBlank @Size(max=160) String name,
 @NotBlank @Size(max=1000) String endpointUrl,
 @Size(max=255)
 @Pattern(regexp="\\s*(?:env:[A-Za-z_][A-Za-z0-9_]*|system:[A-Za-z_][A-Za-z0-9_.-]*)?\\s*",
          message="凭证引用只支持 env:变量名 或 system:属性名；无鉴权可留空，请勿填写 Token 明文")
 String credentialRef,
 boolean enabled
){}
