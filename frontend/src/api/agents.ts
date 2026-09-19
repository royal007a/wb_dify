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
  enabled: boolean
  draftRevision: number
  publishedVersionId: string | null
  publishedVersionNo: number | null
  hasUnpublishedChanges: boolean
  createdAt: string
  updatedAt: string
}

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
export const archiveAgent = (id: string) => del<void>(`/v1/agents/${id}`)
export const publishAgent = (id: string) => post<AgentVersion>(`/v1/agents/${id}/publications`)
export const listAgentVersions = (id: string) => get<AgentVersion[]>(`/v1/agents/${id}/versions`)
