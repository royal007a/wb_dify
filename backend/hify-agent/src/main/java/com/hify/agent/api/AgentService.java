package com.hify.agent.api;

import com.hify.common.PageResult;
import java.util.List;

public interface AgentService {
    String create(AgentUpsertRequest request);
    AgentResponse get(String id);
    PageResult<AgentResponse> list(Integer page, Integer pageSize);
    void update(String id, AgentUpsertRequest request);
    AgentVersionResponse publish(String id);
    List<AgentVersionResponse> versions(String id);
}
