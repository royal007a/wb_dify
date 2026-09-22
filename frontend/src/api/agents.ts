import { del, get, post, put } from '@/utils/request'

export interface Agent extends Record<string, unknown> {
  id: string
  name: string
  description: string
  instructions: string
  providerId: string
  modelId: string
  temperature: number
  maxTokens: number
  maxTurns: number
  maxContextTurns: number
  enabledTools: string[]
  knowledgeBindings: AgentKnowledgeBinding[]
  workflowBinding?: AgentWorkflowBinding | null
  mcpTools: AgentMcpTool[]
  enabled: boolean
  draftRevision: number
  publishedVersionId: string | null
  publishedVersionNo: number | null
  hasUnpublishedChanges: boolean
  createdAt: string
  updatedAt: string
}

export interface AgentKnowledgeBinding {
  knowledgeBaseId: string
  topK: number
  priority: number
  corpusVersionId?: string | null
  manifestDigest?: string | null
}
export interface AgentWorkflowBinding { workflowId:string; workflowVersionId?:string|null; versionNo?:number|null; checksum?:string|null }
export interface AgentMcpTool { serverId:string; serverRevision?:number|null; serverSchemaDigest?:string|null; toolName:string; runtimeToolName?:string|null; description:string; inputSchema:Record<string,unknown>; toolSchemaDigest?:string|null; risk:string }

export interface AgentPayload {
  name: string
  description?: string
  instructions: string
  providerId: string
  modelId: string
  temperature: number
  maxTokens: number
  maxTurns: number
  maxContextTurns: number
  enabledTools: string[]
  enabled: boolean
}

export type AgentUpdatePayload = Omit<AgentPayload, 'enabledTools'>

export interface AgentVersion {
  id: string
  agentId: string
  versionNo: number
  snapshotDigest: string
  createdAt: string
}

export interface AgentPage { data: Agent[]; total: number; page: number; size: number }

export const listAgents = (params: { page: number; pageSize: number }) => get<AgentPage>('/v1/agents', params)
export const getAgent = (id: string) => get<Agent>(`/v1/agents/${id}`)
export const createAgent = (payload: AgentPayload) => post<string>('/v1/agents', payload)
export const updateAgent = (id: string, payload: AgentUpdatePayload) => put<void>(`/v1/agents/${id}`, payload)
export const replaceAgentTools = (id: string, toolIds: string[]) =>
  put<string[]>(`/v1/agents/${id}/tools`, { toolIds })
export const replaceAgentKnowledge = (id: string, bindings: AgentKnowledgeBinding[]) =>
  put<AgentKnowledgeBinding[]>(`/v1/agents/${id}/knowledge-bindings`, { bindings })
export const replaceAgentWorkflow = (id:string, workflowId:string) => put<AgentWorkflowBinding>(`/v1/agents/${id}/workflow-binding`,{workflowId})
export const clearAgentWorkflow = (id:string) => del<void>(`/v1/agents/${id}/workflow-binding`)
export const replaceAgentMcp = (id:string, bindings:Array<{serverId:string;toolNames:string[]}>) => put<AgentMcpTool[]>(`/v1/agents/${id}/mcp-bindings`,{bindings})
export const archiveAgent = (id: string) => del<void>(`/v1/agents/${id}`)
export const publishAgent = (id: string) => post<AgentVersion>(`/v1/agents/${id}/publications`)
export const listAgentVersions = (id: string) => get<AgentVersion[]>(`/v1/agents/${id}/versions`)
