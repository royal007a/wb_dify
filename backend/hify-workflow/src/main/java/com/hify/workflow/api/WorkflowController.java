package com.hify.workflow.api;

import com.hify.common.PageResult; import com.hify.common.Result; import com.hify.workflow.application.*; import jakarta.validation.Valid; import org.springframework.http.*; import org.springframework.web.bind.annotation.*; import java.util.List;
@RestController @RequestMapping("/api/v1")
public class WorkflowController {
 private final WorkflowApplicationService workflows; private final WorkflowEngine engine;
 public WorkflowController(WorkflowApplicationService workflows,WorkflowEngine engine){this.workflows=workflows;this.engine=engine;}
 @PostMapping("/workflows") public ResponseEntity<Result<String>> create(@Valid @RequestBody WorkflowDraftRequest request){return ResponseEntity.status(HttpStatus.CREATED).body(Result.ok(workflows.create(request)));}
 @GetMapping("/workflows") public PageResult<WorkflowResponse> list(@RequestParam(required=false) Integer page,@RequestParam(required=false) Integer pageSize){return workflows.list(page,pageSize);}
 @GetMapping("/workflows/{id}") public Result<WorkflowResponse> get(@PathVariable String id){return Result.ok(workflows.get(id));}
 @PutMapping("/workflows/{id}") public Result<Void> update(@PathVariable String id,@Valid @RequestBody WorkflowDraftRequest request){workflows.update(id,request);return Result.ok();}
 @DeleteMapping("/workflows/{id}") public Result<Void> archive(@PathVariable String id){workflows.archive(id);return Result.ok();}
 @PostMapping("/workflows/{id}/validations") public Result<Void> validate(@PathVariable String id){workflows.validate(id);return Result.ok();}
 @PostMapping("/workflows/{id}/versions") public Result<WorkflowVersionResponse> publish(@PathVariable String id){return Result.ok(workflows.publish(id));}
 @GetMapping("/workflows/{id}/versions") public Result<List<WorkflowVersionResponse>> versions(@PathVariable String id){return Result.ok(workflows.versions(id));}
 @GetMapping("/workflow-versions/{id}") public Result<WorkflowVersionDetail> version(@PathVariable String id){return Result.ok(workflows.versionDetail(id));}
 @PostMapping("/workflow-versions/{id}/runs") public ResponseEntity<Result<WorkflowRunResponse>> run(@PathVariable String id,@Valid @RequestBody WorkflowRunRequest request){return ResponseEntity.status(HttpStatus.ACCEPTED).body(Result.ok(engine.execute(id,request.input())));}
 @GetMapping("/workflow-runs/{id}") public Result<WorkflowRunResponse> run(@PathVariable String id){return Result.ok(engine.get(id));}
}
