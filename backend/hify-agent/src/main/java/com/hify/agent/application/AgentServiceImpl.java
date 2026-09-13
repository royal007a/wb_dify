package com.hify.agent.application;

import com.hify.agent.api.AgentQueryService;
import com.hify.agent.api.AgentResponse;
import com.hify.agent.api.AgentRuntimeSnapshot;
import com.hify.agent.api.AgentService;
import com.hify.agent.api.AgentUpsertRequest;
import com.hify.agent.api.AgentVersionResponse;
import com.hify.common.BizException;
import com.hify.common.ErrorCode;
import com.hify.common.PageResult;
import com.hify.domain.AgentDefinition;
import com.hify.domain.AgentVersion;
import com.hify.infra.AgentDefinitionRepository;
import com.hify.infra.AgentVersionRepository;
import com.hify.provider.api.ProviderQueryService;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

@Service
public class AgentServiceImpl implements AgentService, AgentQueryService {
    private final AgentDefinitionRepository agents;
    private final AgentVersionRepository versions;
    private final ProviderQueryService providers;

    public AgentServiceImpl(AgentDefinitionRepository agents, AgentVersionRepository versions,
                            ProviderQueryService providers) {
        this.agents = agents;
        this.versions = versions;
        this.providers = providers;
    }

    @Override
    @Transactional
    public String create(AgentUpsertRequest request) {
        String name = request.name().trim();
        if (agents.existsByName(name)) throw new BizException(ErrorCode.CONFLICT, "Agent 名称已存在");
        String model = providers.requireEnabledModel(request.providerId(), request.modelId());
        AgentDefinition agent = new AgentDefinition(UUID.randomUUID().toString(), name,
                clean(request.description()), request.instructions().trim(), request.providerId(), model,
                request.temperature(), request.maxTokens(), request.maxTurns(), request.maxContextTurns(),
                tools(request.enabledTools()), request.enabled());
        agents.save(agent);
        return agent.getId();
    }

    @Override
    @Transactional(readOnly = true)
    public AgentResponse get(String id) {
        AgentDefinition agent = requireDraft(id);
        Integer versionNo = agent.getPublishedVersionId() == null ? null
                : versions.findById(agent.getPublishedVersionId()).map(AgentVersion::getVersionNo).orElse(null);
        return response(agent, versionNo);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<AgentResponse> list(Integer page, Integer pageSize) {
        int requestedPage = page == null ? 1 : Math.max(1, page);
        int requestedSize = pageSize == null ? 20 : Math.min(100, Math.max(1, pageSize));
        var result = agents.findAll(PageRequest.of(requestedPage - 1, requestedSize,
                Sort.by(Sort.Direction.DESC, "updatedAt")));
        return PageResult.of(result.getContent().stream().map(agent -> {
            Integer versionNo = agent.getPublishedVersionId() == null ? null
                    : versions.findById(agent.getPublishedVersionId()).map(AgentVersion::getVersionNo).orElse(null);
            return response(agent, versionNo);
        }).toList(), result.getTotalElements(), requestedPage, requestedSize);
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = "agent-cache", allEntries = true)
    public void update(String id, AgentUpsertRequest request) {
        AgentDefinition agent = requireDraft(id);
        String name = request.name().trim();
        if (agents.existsByNameAndIdNot(name, id)) throw new BizException(ErrorCode.CONFLICT, "Agent 名称已存在");
        String model = providers.requireEnabledModel(request.providerId(), request.modelId());
        agent.updateDraft(name, clean(request.description()), request.instructions().trim(),
                request.providerId(), model, request.temperature(), request.maxTokens(),
                request.maxTurns(), request.maxContextTurns(), tools(request.enabledTools()), request.enabled());
        agents.save(agent);
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = "agent-cache", allEntries = true)
    public AgentVersionResponse publish(String id) {
        AgentDefinition draft = requireDraft(id);
        String model = providers.requireEnabledModel(draft.getProviderId(), draft.getModel());
        if (!model.equals(draft.getModel())) throw new BizException(ErrorCode.CONFLICT, "Agent 草稿模型已失效");
        int versionNo = Math.toIntExact(versions.countByAgentId(id) + 1);
        String versionId = UUID.randomUUID().toString();
        AgentVersion version = new AgentVersion(versionId, id, versionNo, draft,
                digest(draft), Instant.now());
        versions.save(version);
        draft.markPublished(versionId);
        agents.save(draft);
        return version(version);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentVersionResponse> versions(String id) {
        requireDraft(id);
        return versions.findByAgentIdOrderByVersionNoDesc(id).stream().map(this::version).toList();
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "agent-cache", key = "'current:' + #agentId")
    public AgentRuntimeSnapshot requirePublished(String agentId) {
        AgentDefinition draft = requireDraft(agentId);
        if (draft.getPublishedVersionId() == null) {
            throw new BizException(ErrorCode.CONFLICT, "Agent 尚未发布");
        }
        return requireVersion(draft.getPublishedVersionId());
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "agent-cache", key = "'version:' + #versionId")
    public AgentRuntimeSnapshot requireVersion(String versionId) {
        AgentVersion version = versions.findById(versionId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "Agent 发布版本不存在"));
        if (!version.isEnabled()) throw new BizException(ErrorCode.CONFLICT, "Agent 发布版本已停用");
        return snapshot(version);
    }

    private AgentDefinition requireDraft(String id) {
        return agents.findById(id).orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "Agent 不存在"));
    }

    private AgentResponse response(AgentDefinition agent, Integer publishedVersionNo) {
        return new AgentResponse(agent.getId(), agent.getName(), agent.getDescription(), agent.getInstructions(),
                agent.getProviderId(), agent.getModel(), agent.getTemperature(), agent.getMaxTokens(),
                agent.getMaxTurns(), agent.getMaxContextTurns(), splitTools(agent.getEnabledTools()),
                agent.isEnabled(), agent.getDraftRevision(), agent.getPublishedVersionId(), publishedVersionNo,
                agent.getCreatedAt(), agent.getUpdatedAt());
    }

    private AgentVersionResponse version(AgentVersion version) {
        return new AgentVersionResponse(version.getId(), version.getAgentId(), version.getVersionNo(),
                version.getSnapshotDigest(), version.getCreatedAt());
    }

    private AgentRuntimeSnapshot snapshot(AgentVersion version) {
        return new AgentRuntimeSnapshot(version.getId(), version.getAgentId(), version.getVersionNo(),
                version.getSnapshotDigest(), version.getName(), version.getInstructions(),
                version.getProviderId(), version.getModel(), version.getTemperature(), version.getMaxTokens(),
                version.getMaxTurns(), version.getMaxContextTurns(), splitTools(version.getEnabledTools()),
                version.isEnabled());
    }

    private String digest(AgentDefinition draft) {
        String canonical = String.join("\u001f", draft.getId(), draft.getName(), draft.getInstructions(),
                draft.getProviderId(), draft.getModel(), String.valueOf(draft.getTemperature()),
                String.valueOf(draft.getMaxTokens()), String.valueOf(draft.getMaxTurns()),
                String.valueOf(draft.getMaxContextTurns()), String.valueOf(draft.getEnabledTools()),
                String.valueOf(draft.isEnabled()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String tools(List<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) normalized.add(value.trim());
        }
        return String.join(",", normalized);
    }

    private static List<String> splitTools(String value) {
        return value == null || value.isBlank() ? List.of() : Arrays.stream(value.split(","))
                .map(String::trim).filter(item -> !item.isBlank()).toList();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
