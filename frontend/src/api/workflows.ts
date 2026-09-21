import { del, get, post } from '@/utils/request'
import type { HifyPageResult } from '@/components/HifyTable.vue'
export interface Workflow extends Record<string, unknown>{id:string;name:string;description:string;schemaVersion:number;draftRevision:number;publishedVersionId?:string;createdAt:string;updatedAt:string}
export interface WorkflowVersion{id:string;workflowId:string;versionNo:number;schemaVersion:number;checksum:string;createdAt:string}
export interface WorkflowRun{id:string;workflowVersionId:string;workflowDigest:string;status:string;input:string;output?:string;errorMessage?:string;elapsedMs?:number;nodes:Array<{sequence:number;nodeKey:string;nodeType:string;status:string;elapsedMs?:number}>}
export const listWorkflows=(params:{page:number;pageSize:number})=>get<HifyPageResult<Workflow>>('/v1/workflows',params)
export const createWorkflow=(data:object)=>post<string>('/v1/workflows',data)
export const archiveWorkflow=(id:string)=>del<void>(`/v1/workflows/${id}`)
export const validateWorkflow=(id:string)=>post<void>(`/v1/workflows/${id}/validations`)
export const publishWorkflow=(id:string)=>post<WorkflowVersion>(`/v1/workflows/${id}/versions`)
export const runWorkflow=(versionId:string,input:string)=>post<WorkflowRun>(`/v1/workflow-versions/${versionId}/runs`,{input})
