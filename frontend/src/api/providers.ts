import { del, get, post, put } from '@/utils/request'

export type ProviderType = 'OPENAI' | 'ANTHROPIC' | 'GEMINI' | 'OPENAI_COMPATIBLE'

export interface ProviderModel {
  id?: number
  displayName: string
  modelId: string
  enabled: boolean
  isDefault: boolean
}

export interface ProviderHealth {
  status: 'UNKNOWN' | 'HEALTHY' | 'UNHEALTHY'
  latencyMs: number | null
  errorCode: string | null
  message: string | null
  checkedAt: string | null
}

export interface Provider extends Record<string, unknown> {
  id: string
  name: string
  type: ProviderType
  baseUrl: string
  enabled: boolean
  credentialConfigured: boolean
  defaultModelId: string
  models: ProviderModel[]
  health: ProviderHealth
  createdAt: string
  updatedAt: string
}

export interface ProviderPayload {
  name: string
  type: ProviderType
  baseUrl?: string
  auth?: { credentialRef: string; headerName?: string; prefix?: string }
  enabled?: boolean
  models: ProviderModel[]
}

export interface ProviderPage {
  data: Provider[]
  total: number
  page: number
  size: number
}

export interface ConnectionTest {
  success: boolean
  latencyMs: number
  providerCode: string
  message: string
  checkedAt: string
}

export const listProviders = (params: { page: number; pageSize: number }) =>
  get<ProviderPage>('/v1/providers', params)
export const getProvider = (id: string) => get<Provider>(`/v1/providers/${id}`)
export const createProvider = (payload: ProviderPayload) => post<string>('/v1/providers', payload)
export const updateProvider = (id: string, payload: ProviderPayload) => put<void>(`/v1/providers/${id}`, payload)
export const deleteProvider = (id: string) => del<void>(`/v1/providers/${id}`)
export const testProvider = (id: string) => post<ConnectionTest>(`/v1/providers/${id}/connection-tests`)
