package com.hify.mcp.api;
import jakarta.validation.constraints.*;
public record McpServerRequest(@NotBlank @Size(max=160) String name,@NotBlank @Size(max=1000) String endpointUrl,@Size(max=255) String credentialRef,boolean enabled){}
