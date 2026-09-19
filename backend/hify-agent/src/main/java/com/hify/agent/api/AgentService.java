package com.hify.agent.api;

import com.hify.common.PageResult;
import java.util.List;

public interface AgentService {
    String create(AgentUpsertRequest request);
    AgentResponse get(String id);
    PageResult<AgentResponse> list(Integer page, Integer pageSize);
    void update(String id, AgentUpdateRequest request);
    List<String> replaceTools(String id, AgentToolBindingRequest request);
    void archive(String id);
    AgentVersionResponse publish(String id);
    List<AgentVersionResponse> versions(String id);
}
