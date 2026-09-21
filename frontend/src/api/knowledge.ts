import { del, get, post } from '@/utils/request'
import type { HifyPageResult } from '@/components/HifyTable.vue'
export interface KnowledgeBase extends Record<string, unknown> { id:string; name:string; description:string; chunkSize:number; chunkOverlap:number; enabled:boolean; documentCount:number; createdAt:string; updatedAt:string }
export interface KnowledgeDocument { id:string; knowledgeBaseId:string; name:string; mediaType:string; fileSize:number; checksum:string; documentVersion:number; indexingState:string; errorMessage?:string; chunkCount:number; createdAt:string; updatedAt:string }
export interface KnowledgeCitation { chunkId:string; documentId:string; documentVersion:number; ordinal:number; content:string; digest:string; score:number; rank:number }
export const listKnowledgeBases=(params:{page:number;pageSize:number})=>get<HifyPageResult<KnowledgeBase>>('/v1/knowledge-bases',params)
export const createKnowledgeBase=(data:{name:string;description:string;chunkSize:number;chunkOverlap:number;enabled:boolean})=>post<string>('/v1/knowledge-bases',data)
export const archiveKnowledgeBase=(id:string)=>del<void>(`/v1/knowledge-bases/${id}`)
export const listDocuments=(id:string)=>get<HifyPageResult<KnowledgeDocument>>(`/v1/knowledge-bases/${id}/documents`,{page:1,pageSize:100})
export const archiveDocument=(id:string)=>del<void>(`/v1/documents/${id}`)
export const uploadDocument=(id:string,file:File)=>{const form=new FormData();form.append('file',file);return post<string>(`/v1/knowledge-bases/${id}/documents`,form,{headers:{'Content-Type':'multipart/form-data'}})}
export const testRetrieval=(id:string,query:string)=>post<KnowledgeCitation[]>(`/v1/knowledge-bases/${id}/retrieval-tests`,{query,topK:5})
