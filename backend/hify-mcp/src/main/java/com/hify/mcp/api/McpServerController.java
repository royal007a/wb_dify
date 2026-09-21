package com.hify.mcp.api;
import com.hify.common.*; import com.hify.mcp.application.McpRegistryService; import jakarta.validation.Valid; import org.springframework.http.*; import org.springframework.web.bind.annotation.*; import java.util.List;
@RestController @RequestMapping("/api/v1/mcp-servers")
public class McpServerController {
 private final McpRegistryService service; public McpServerController(McpRegistryService service){this.service=service;}
 @PostMapping public ResponseEntity<Result<String>> create(@Valid @RequestBody McpServerRequest request){return ResponseEntity.status(HttpStatus.CREATED).body(Result.ok(service.create(request)));}
 @GetMapping public Result<List<McpServerResponse>> list(){return Result.ok(service.list());}
 @GetMapping("/{id}") public Result<McpServerResponse> get(@PathVariable String id){return Result.ok(service.get(id));}
 @PutMapping("/{id}") public Result<Void> update(@PathVariable String id,@Valid @RequestBody McpServerRequest request){service.update(id,request);return Result.ok();}
 @DeleteMapping("/{id}") public Result<Void> archive(@PathVariable String id){service.archive(id);return Result.ok();}
 @PostMapping("/{id}/tools:refresh") public Result<List<McpToolResponse>> refresh(@PathVariable String id){return Result.ok(service.discover(id));}
 @GetMapping("/{id}/tools") public Result<List<McpToolResponse>> tools(@PathVariable String id){return Result.ok(service.tools(id));}
 @PostMapping("/{id}/tools/{toolName}:call") public Result<McpDebugResponse> call(@PathVariable String id,@PathVariable String toolName,@Valid @RequestBody McpDebugRequest request){return Result.ok(service.debug(id,toolName,request));}
}
