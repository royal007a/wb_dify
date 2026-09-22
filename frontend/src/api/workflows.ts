import { del, get, post, put } from '@/utils/request'
import type { HifyPageResult } from '@/components/HifyTable.vue'
export interface WorkflowNode{nodeKey:string;type:string;name:string;config:Record<string,unknown>}
export interface WorkflowEdge{edgeKey:string;sourceNodeKey:string;targetNodeKey:string;condition?:string;defaultBranch:boolean}
export interface WorkflowDraft{name:string;description:string;schemaVersion:number;nodes:WorkflowNode[];edges:WorkflowEdge[]}
export interface Workflow extends Record<string, unknown>,WorkflowDraft{id:string;draftRevision:number;publishedVersionId?:string;createdAt:string;updatedAt:string}
export interface WorkflowVersion{id:string;workflowId:string;versionNo:number;schemaVersion:number;checksum:string;createdAt:string}
export interface WorkflowVersionDetail extends WorkflowVersion{nodes:WorkflowNode[];edges:WorkflowEdge[]}
export interface WorkflowRun{id:string;workflowVersionId:string;workflowDigest:string;status:string;input:string;output?:string;errorMessage?:string;elapsedMs?:number;nodes:Array<{sequence:number;nodeKey:string;nodeType:string;status:string;elapsedMs?:number}>}
export const listWorkflows=(params:{page:number;pageSize:number})=>get<HifyPageResult<Workflow>>('/v1/workflows',params)
export const createWorkflow=(data:object)=>post<string>('/v1/workflows',data)
export const getWorkflow=(id:string)=>get<Workflow>(`/v1/workflows/${id}`)
export const updateWorkflow=(id:string,data:WorkflowDraft)=>put<void>(`/v1/workflows/${id}`,data)
export const archiveWorkflow=(id:string)=>del<void>(`/v1/workflows/${id}`)
export const validateWorkflow=(id:string)=>post<void>(`/v1/workflows/${id}/validations`)
export const publishWorkflow=(id:string)=>post<WorkflowVersion>(`/v1/workflows/${id}/versions`)
export const listWorkflowVersions=(id:string)=>get<WorkflowVersion[]>(`/v1/workflows/${id}/versions`)
export const getWorkflowVersion=(id:string)=>get<WorkflowVersionDetail>(`/v1/workflow-versions/${id}`)
export const runWorkflow=(versionId:string,input:string)=>post<WorkflowRun>(`/v1/workflow-versions/${versionId}/runs`,{input})
