package com.hify.agent.application;

import com.hify.agent.api.AgentQueryService;
import com.hify.agent.api.AgentResponse;
import com.hify.agent.api.AgentRuntimeSnapshot;
import com.hify.agent.api.AgentService;
import com.hify.agent.api.AgentToolBindingRequest;
import com.hify.agent.api.AgentUpdateRequest;
import com.hify.agent.api.AgentUpsertRequest;
import com.hify.agent.api.AgentVersionResponse;
import com.hify.common.BizException;
import com.hify.common.ErrorCode;
import com.hify.common.PageResult;
import com.hify.domain.AgentDefinition;
import com.hify.domain.AgentToolBinding;
import com.hify.domain.AgentVersion;
import com.hify.domain.AgentVersionToolBinding;
import com.hify.infra.AgentDefinitionRepository;
import com.hify.infra.AgentToolBindingRepository;
import com.hify.infra.AgentVersionRepository;
import com.hify.infra.AgentVersionToolBindingRepository;
import com.hify.provider.api.ProviderQueryService;
import com.hify.tool.api.ToolCatalog;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AgentServiceImpl implements AgentService, AgentQueryService {
    private final AgentDefinitionRepository agents;
    private final AgentVersionRepository versions;
    private final AgentToolBindingRepository draftToolBindings;
    private final AgentVersionToolBindingRepository versionToolBindings;
    private final ProviderQueryService providers;
    private final ToolCatalog tools;

    public AgentServiceImpl(AgentDefinitionRepository agents, AgentVersionRepository versions,
                            AgentToolBindingRepository draftToolBindings,
                            AgentVersionToolBindingRepository versionToolBindings,
                            ProviderQueryService providers, ToolCatalog tools) {
        this.agents = agents;
        this.versions = versions;
        this.draftToolBindings = draftToolBindings;
        this.versionToolBindings = versionToolBindings;
        this.providers = providers;
        this.tools = tools;
    }

    @Override
    @Transactional
    public String create(AgentUpsertRequest request) {
        String name = request.name().trim();
        if (agents.existsByName(name)) throw duplicateName();
        String model = providers.requireEnabledModel(request.providerId(), request.modelId());
        List<String> toolNames = validateTools(request.enabledTools());
        AgentDefinition agent = new AgentDefinition(UUID.randomUUID().toString(), name,
                clean(request.description()), request.instructions().trim(), request.providerId().trim(), model,
                request.temperature(), request.maxTokens(), request.maxTurns(), request.maxContextTurns(),
                request.enabled());
        try {
            agents.saveAndFlush(agent);
        } catch (DataIntegrityViolationException exception) {
            if (isDuplicateName(exception)) throw duplicateName();
            throw exception;
        }
        saveDraftBindings(agent.getId(), toolNames);
        return agent.getId();
    }

    @Override
    @Transactional(readOnly = true)
    public AgentResponse get(String id) {
        AgentDefinition agent = requireDraft(id);
        List<String> enabledTools = draftTools(List.of(id)).getOrDefault(id, List.of());
        AgentVersion published = publishedVersion(agent, publishedVersions(List.of(agent)));
        List<String> publishedTools = published == null ? List.of()
                : versionTools(List.of(published.getId())).getOrDefault(published.getId(), List.of());
        return response(agent, published, enabledTools, publishedTools);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<AgentResponse> list(Integer page, Integer pageSize) {
        int requestedPage = page == null ? 1 : Math.max(1, page);
        int requestedSize = pageSize == null ? 20 : Math.min(100, Math.max(1, pageSize));
        var result = agents.findAllByArchivedAtIsNull(PageRequest.of(requestedPage - 1, requestedSize,
                Sort.by(Sort.Direction.DESC, "updatedAt")));
        List<AgentDefinition> content = result.getContent();
        Map<String, List<String>> toolsByAgent = draftTools(content.stream().map(AgentDefinition::getId).toList());
        Map<String, AgentVersion> versionsById = publishedVersions(content);
        Map<String, List<String>> toolsByVersion = versionTools(versionsById.keySet());
        List<AgentResponse> responses = content.stream()
                .map(agent -> response(agent, publishedVersion(agent, versionsById),
                        toolsByAgent.getOrDefault(agent.getId(), List.of()),
                        toolsByVersion.getOrDefault(agent.getPublishedVersionId(), List.of())))
                .toList();
        return PageResult.of(responses, result.getTotalElements(), requestedPage, requestedSize);
    }

    @Override
    @Transactional
    public void update(String id, AgentUpdateRequest request) {
        AgentDefinition agent = requireDraft(id);
        String name = request.name().trim();
        if (agents.existsByNameAndIdNot(name, id)) throw duplicateName();
        String model = providers.requireEnabledModel(request.providerId(), request.modelId());
        agent.updateDraft(name, clean(request.description()), request.instructions().trim(),
                request.providerId().trim(), model, request.temperature(), request.maxTokens(),
                request.maxTurns(), request.maxContextTurns(), request.enabled());
        try {
            agents.saveAndFlush(agent);
        } catch (DataIntegrityViolationException exception) {
            if (isDuplicateName(exception)) throw duplicateName();
            throw exception;
        }
    }

    @Override
    @Transactional
    public List<String> replaceTools(String id, AgentToolBindingRequest request) {
        AgentDefinition agent = requireDraft(id);
        List<String> toolNames = validateTools(request.toolIds());
        draftToolBindings.deleteByAgentId(id);
        saveDraftBindings(id, toolNames);
        agent.touchDraft();
        agents.save(agent);
        return toolNames;
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = "agent-cache", key = "'current:' + #id")
    public void archive(String id) {
        AgentDefinition agent = requireDraft(id);
        draftToolBindings.deleteByAgentId(id);
        agent.archive(Instant.now());
        agents.save(agent);
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = "agent-cache", key = "'current:' + #id")
    public AgentVersionResponse publish(String id) {
        AgentDefinition draft = requireDraft(id);
        String model = providers.requireEnabledModel(draft.getProviderId(), draft.getModel());
        if (!model.equals(draft.getModel())) throw new BizException(ErrorCode.CONFLICT, "Agent 草稿模型已失效");
        List<String> toolNames = draftTools(List.of(id)).getOrDefault(id, List.of());
        validateTools(toolNames);
        int versionNo = Math.toIntExact(versions.countByAgentId(id) + 1);
        String versionId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        AgentVersion version = new AgentVersion(versionId, id, versionNo, draft,
                digest(draft, toolNames), now);
        versions.save(version);
        versionToolBindings.saveAll(toolNames.stream()
                .map(tool -> new AgentVersionToolBinding(versionId, tool, now)).toList());
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
        return snapshot(requireVersionEntity(draft.getPublishedVersionId()));
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "agent-cache", key = "'version:' + #versionId")
    public AgentRuntimeSnapshot requireVersion(String versionId) {
        return snapshot(requireVersionEntity(versionId));
    }

    private AgentDefinition requireDraft(String id) {
        return agents.findByIdAndArchivedAtIsNull(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "Agent 不存在或已归档"));
    }

    private AgentVersion requireVersionEntity(String versionId) {
        AgentVersion version = versions.findById(versionId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "Agent 发布版本不存在"));
        if (!version.isEnabled()) throw new BizException(ErrorCode.CONFLICT, "Agent 发布版本已停用");
        return version;
    }

    private AgentResponse response(AgentDefinition agent, AgentVersion publishedVersion,
                                   List<String> enabledTools, List<String> publishedTools) {
        Integer publishedVersionNo = publishedVersion == null ? null : publishedVersion.getVersionNo();
        boolean hasUnpublishedChanges = publishedVersion == null
                || !digest(agent, enabledTools).equals(digest(publishedVersion, publishedTools));
        return new AgentResponse(agent.getId(), agent.getName(), agent.getDescription(), agent.getInstructions(),
                agent.getProviderId(), agent.getModel(), agent.getTemperature(), agent.getMaxTokens(),
                agent.getMaxTurns(), agent.getMaxContextTurns(), enabledTools,
                agent.isEnabled(), agent.getDraftRevision(), agent.getPublishedVersionId(), publishedVersionNo,
                hasUnpublishedChanges,
                agent.getCreatedAt(), agent.getUpdatedAt());
    }

    private AgentVersionResponse version(AgentVersion version) {
        return new AgentVersionResponse(version.getId(), version.getAgentId(), version.getVersionNo(),
                version.getSnapshotDigest(), version.getCreatedAt());
    }

    private AgentRuntimeSnapshot snapshot(AgentVersion version) {
        List<String> enabledTools = versionToolBindings.findByVersionId(version.getId()).stream()
                .map(AgentVersionToolBinding::getToolName).toList();
        return new AgentRuntimeSnapshot(version.getId(), version.getAgentId(), version.getVersionNo(),
                version.getSnapshotDigest(), version.getName(), version.getInstructions(),
                version.getProviderId(), version.getModel(), version.getTemperature(), version.getMaxTokens(),
                version.getMaxTurns(), version.getMaxContextTurns(), enabledTools, version.isEnabled());
    }

    private String digest(AgentDefinition draft, List<String> toolNames) {
        return digest(draft.getId(), draft.getName(), draft.getInstructions(), draft.getProviderId(),
                draft.getModel(), draft.getTemperature(), draft.getMaxTokens(), draft.getMaxTurns(),
                draft.getMaxContextTurns(), toolNames, draft.isEnabled());
    }

    private String digest(AgentVersion version, List<String> toolNames) {
        return digest(version.getAgentId(), version.getName(), version.getInstructions(), version.getProviderId(),
                version.getModel(), version.getTemperature(), version.getMaxTokens(), version.getMaxTurns(),
                version.getMaxContextTurns(), toolNames, version.isEnabled());
    }

    private String digest(String agentId, String name, String instructions, String providerId,
                          String model, double temperature, int maxTokens, int maxTurns,
                          int maxContextTurns, List<String> toolNames, boolean enabled) {
        String canonical = String.join("\u001f", agentId, name, instructions, providerId, model,
                String.valueOf(temperature), String.valueOf(maxTokens), String.valueOf(maxTurns),
                String.valueOf(maxContextTurns), String.join(",", new TreeSet<>(toolNames)),
                String.valueOf(enabled));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private List<String> validateTools(Collection<String> requested) {
        if (requested == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "工具绑定不能为空");
        }
        TreeSet<String> normalized = new TreeSet<>();
        for (String value : requested) {
            String name = value.trim();
            if (!normalized.add(name)) {
                throw new BizException(ErrorCode.PARAM_ERROR, "工具不能重复绑定: " + name);
            }
        }
        TreeSet<String> unknown = new TreeSet<>(normalized);
        unknown.removeAll(tools.availableToolNames());
        if (!unknown.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "工具不存在或不可用: " + String.join(",", unknown));
        }
        return List.copyOf(normalized);
    }

    private void saveDraftBindings(String agentId, List<String> toolNames) {
        Instant now = Instant.now();
        draftToolBindings.saveAll(toolNames.stream()
                .map(tool -> new AgentToolBinding(agentId, tool, now)).toList());
    }

    private Map<String, List<String>> draftTools(Collection<String> agentIds) {
        if (agentIds.isEmpty()) return Map.of();
        Map<String, List<String>> result = new HashMap<>();
        draftToolBindings.findByAgentIds(agentIds).forEach(binding -> result
                .computeIfAbsent(binding.getAgentId(), ignored -> new java.util.ArrayList<>())
                .add(binding.getToolName()));
        return result;
    }

    private Map<String, AgentVersion> publishedVersions(Collection<AgentDefinition> definitions) {
        List<String> versionIds = definitions.stream().map(AgentDefinition::getPublishedVersionId)
                .filter(id -> id != null && !id.isBlank()).distinct().toList();
        if (versionIds.isEmpty()) return Map.of();
        return versions.findAllById(versionIds).stream().collect(Collectors.toMap(
                AgentVersion::getId, version -> version, (left, right) -> left));
    }

    private Map<String, List<String>> versionTools(Collection<String> versionIds) {
        if (versionIds.isEmpty()) return Map.of();
        Map<String, List<String>> result = new HashMap<>();
        versionToolBindings.findByVersionIds(versionIds).forEach(binding -> result
                .computeIfAbsent(binding.getAgentVersionId(), ignored -> new java.util.ArrayList<>())
                .add(binding.getToolName()));
        return result;
    }

    private AgentVersion publishedVersion(AgentDefinition definition, Map<String, AgentVersion> versionsById) {
        String versionId = definition.getPublishedVersionId();
        return versionId == null ? null : versionsById.get(versionId);
    }

    private static BizException duplicateName() {
        return new BizException(ErrorCode.CONFLICT, "Agent 名称已存在");
    }

    private static boolean isDuplicateName(DataIntegrityViolationException exception) {
        Throwable cause = exception;
        while (cause != null) {
            String message = cause.getMessage();
            if (message != null && message.toLowerCase().contains("uq_agent_definition_name")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
