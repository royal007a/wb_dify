import { del, get, post } from '@/utils/request'
export interface McpServer extends Record<string,unknown>{id:string;name:string;transport:string;endpointUrl:string;credentialRef?:string;enabled:boolean;serverRevision:number;schemaDigest?:string;status:string;lastError?:string;createdAt:string;updatedAt:string}
export interface McpTool{name:string;description:string;inputSchema:Record<string,unknown>;risk:string;schemaDigest:string;serverRevision:number}
export interface McpDebugResult{callId:string;toolName:string;serverRevision:number;schemaDigest:string;result:unknown;error:boolean;elapsedMs:number}
export const listMcpServers=()=>get<McpServer[]>('/v1/mcp-servers')
export const createMcpServer=(data:{name:string;endpointUrl:string;credentialRef?:string;enabled:boolean})=>post<string>('/v1/mcp-servers',data)
export const archiveMcpServer=(id:string)=>del<void>(`/v1/mcp-servers/${id}`)
export const refreshMcpTools=(id:string)=>post<McpTool[]>(`/v1/mcp-servers/${id}/tools:refresh`)
export const listMcpTools=(id:string)=>get<McpTool[]>(`/v1/mcp-servers/${id}/tools`)
export const callMcpTool=(id:string,name:string,args:Record<string,unknown>)=>post<McpDebugResult>(`/v1/mcp-servers/${id}/tools/${encodeURIComponent(name)}:call`,{arguments:args})
