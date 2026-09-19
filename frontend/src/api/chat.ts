import { listAgents, type Agent } from '@/api/agents'

export interface Conversation {
  id: string
  agentId: string
  agentVersionId: string
  title: string
  createdAt: string
  updatedAt: string
}

export interface Run {
  id: string
  conversationId: string
  state: string
  terminalReason?: string
  outputMessage?: string
  turns: number
  toolCalls: number
  agentVersionId: string
  agentSnapshotDigest: string
  streamUrl: string
}

export interface ResumeState {
  runId: string
  gapIds: string[]
}

const apiBaseUrl = (import.meta.env.VITE_API_BASE_URL || '/api').replace(/\/$/, '')

export function runtimeUrl(path: string) {
  const normalized = path.startsWith('/api/') ? path.slice('/api'.length) : path
  return `${apiBaseUrl}${normalized.startsWith('/') ? normalized : `/${normalized}`}`
}

async function requestJson<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(runtimeUrl(path), init)
  if (!response.ok) throw new Error(await errorMessage(response))
  return response.json() as Promise<T>
}

export async function listRunnableAgents(): Promise<Agent[]> {
  const page = await listAgents({ page: 1, pageSize: 100 })
  return page.data.filter(agent => agent.enabled && Boolean(agent.publishedVersionId))
}

export const createConversation = (agentId: string) => requestJson<Conversation>('/v1/conversations', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ agentId, title: 'Hify Playground' }),
})

export const createRun = (conversationId: string, message: string, resume?: ResumeState) =>
  requestJson<Run>(`/v1/conversations/${conversationId}/runs`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify({ message, ...(resume ? { resume } : {}) }),
  })

export const getRun = (runId: string) => requestJson<Run>(`/v1/runs/${runId}`)

export const cancelRun = (runId: string) => requestJson<Run>(`/v1/runs/${runId}/cancellations`, {
  method: 'POST',
})

async function errorMessage(response: Response) {
  try {
    const payload = await response.json()
    return payload.message ?? `HTTP ${response.status}`
  } catch { return `HTTP ${response.status}` }
}
