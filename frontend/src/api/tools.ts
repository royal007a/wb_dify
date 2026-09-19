import { get } from '@/utils/request'

export interface ToolCatalogItem {
  id: string
  displayName: string
  description: string
  source: 'BUILTIN' | 'MCP' | string
  risk: 'READ' | 'WRITE' | 'EXECUTE' | 'EXTERNAL' | string
  available: boolean
}

export const listTools = () => get<ToolCatalogItem[]>('/v1/tools')
