<script setup lang="ts">
import { computed, ref } from 'vue'
import { Delete, Plus, Promotion } from '@element-plus/icons-vue'
import type { FormRules } from 'element-plus'
import HifyFormDialog from '@/components/HifyFormDialog.vue'
import HifyTable, { type HifyColumn, type HifyPageResult } from '@/components/HifyTable.vue'
import { notifySuccess } from '@/utils/notify'
import { archiveAgent, clearAgentWorkflow, createAgent, getAgent, listAgents, publishAgent, replaceAgentKnowledge, replaceAgentMcp, replaceAgentTools, replaceAgentWorkflow, updateAgent,
  type Agent, type AgentPayload } from '@/api/agents'
import { listProviders, type Provider } from '@/api/providers'
import { listTools, type ToolCatalogItem } from '@/api/tools'
import { useConfirm } from '@/composables/useConfirm'
import { listKnowledgeBases, type KnowledgeBase } from '@/api/knowledge'
import { listWorkflows, type Workflow } from '@/api/workflows'
import { listMcpServers, listMcpTools, type McpServer, type McpTool } from '@/api/mcp'

interface AgentForm extends Record<string, unknown>, AgentPayload { knowledgeBaseIds: string[]; workflowId:string; mcpToolKeys:string[] }
type TableExpose = { refresh: (resetPage?: boolean) => Promise<void> }
type DialogExpose = { open: (data?: Partial<AgentForm>) => Promise<void> }

const columns: HifyColumn<Agent>[] = [
  { label: '名称', prop: 'name', minWidth: 160 },
  { label: '模型', prop: 'modelId', minWidth: 150 },
  { label: '工具', width: 70, slot: 'tools' },
  { label: '知识库', width: 78, slot: 'knowledge' },
  { label: '流程/MCP', width: 90, slot: 'capabilities' },
  { label: 'Temperature', prop: 'temperature', width: 115 },
  { label: '草稿', width: 74, slot: 'draft' },
  { label: '发布状态', width: 145, slot: 'published' },
  { label: '状态', width: 90, slot: 'status' },
  { label: '创建时间', width: 170, slot: 'createdAt' },
  { label: '操作', width: 210, slot: 'actions', align: 'right' },
]
const tableRef = ref<TableExpose>()
const dialogRef = ref<DialogExpose>()
const dialogVisible = ref(false)
const editingId = ref<string>()
const publishingId = ref<string>()
const providers = ref<Provider[]>([])
const toolCatalog = ref<ToolCatalogItem[]>([])
const knowledgeBases = ref<KnowledgeBase[]>([])
const workflows=ref<Workflow[]>([]);const mcpServers=ref<McpServer[]>([]);const mcpTools=ref<Array<McpTool&{serverId:string;serverName:string}>>([])
const archiveTarget = ref<Agent>()
const dialogTitle = computed(() => editingId.value ? '编辑 Agent 草稿' : '创建 Agent 草稿')
const archiveMessage = computed(() => `确认归档「${archiveTarget.value?.name ?? '该 Agent'}」？归档后不能创建新会话，历史发布版本仍会保留。`)
const { confirming: archiving, execute: confirmArchive } = useConfirm(
  archiveMessage,
  archiveAgent,
  'Agent 已归档',
)
const rules: FormRules<AgentForm> = {
  name: [{ required: true, message: '请输入 Agent 名称', trigger: 'blur' }],
  instructions: [{ required: true, message: '请输入系统指令', trigger: 'blur' }],
  providerId: [{ required: true, message: '请选择 Provider', trigger: 'change' }],
  modelId: [{ required: true, message: '请选择模型', trigger: 'change' }],
}
const selectedProvider = (form: AgentForm) => providers.value.find(item => item.id === form.providerId)

function emptyForm(): AgentForm {
  return { name: '', description: '', instructions: '', providerId: '', modelId: '', temperature: 0.2,
    maxTokens: 2048, maxTurns: 6, maxContextTurns: 10, enabledTools: [], knowledgeBaseIds: [], workflowId:'', mcpToolKeys:[], enabled: true }
}
function loadAgents(params: { page: number; pageSize: number }): Promise<HifyPageResult<Agent>> {
  return listAgents(params)
}
async function ensureProviders() {
  if (providers.value.length) return
  providers.value = (await listProviders({ page: 1, pageSize: 100 })).data.filter(item => item.enabled)
}
async function ensureTools() {
  if (toolCatalog.value.length) return
  toolCatalog.value = await listTools()
}
async function ensureKnowledgeBases() {
  if (knowledgeBases.value.length) return
  knowledgeBases.value = (await listKnowledgeBases({ page: 1, pageSize: 100 })).data.filter(item => item.enabled)
}
async function ensureCapabilities(){if(!workflows.value.length)workflows.value=(await listWorkflows({page:1,pageSize:100})).data.filter(item=>item.publishedVersionId);if(!mcpServers.value.length){mcpServers.value=(await listMcpServers()).filter(item=>item.enabled&&item.status==='READY');mcpTools.value=(await Promise.all(mcpServers.value.map(async server=>(await listMcpTools(server.id)).filter(tool=>tool.risk==='READ').map(tool=>({...tool,serverId:server.id,serverName:server.name}))))).flat()}}
async function ensureReferences() {
  await Promise.all([ensureProviders(), ensureTools(), ensureKnowledgeBases(),ensureCapabilities()])
}
async function openCreate() {
  editingId.value = undefined
  await ensureReferences()
  await dialogRef.value?.open()
}
async function openEdit(row: Agent) {
  editingId.value = row.id
  await ensureReferences()
  const agent = await getAgent(row.id)
  await dialogRef.value?.open({ ...agent, knowledgeBaseIds: agent.knowledgeBindings.map(item => item.knowledgeBaseId),workflowId:agent.workflowBinding?.workflowId??'',mcpToolKeys:agent.mcpTools.map(item=>`${item.serverId}::${item.toolName}`) })
}
function onProviderChanged(form: AgentForm) {
  const provider = selectedProvider(form)
  form.modelId = provider?.defaultModelId ?? ''
}
async function save(form: AgentForm, done: (success?: boolean) => void) {
  try {
    const { knowledgeBaseIds,workflowId,mcpToolKeys, ...values } = form
    const payload: AgentPayload = { ...values, enabledTools: form.enabledTools ?? [] }
    let agentId = editingId.value
    if (editingId.value) {
      const { enabledTools, ...configuration } = payload
      await updateAgent(editingId.value, configuration)
      await replaceAgentTools(editingId.value, enabledTools)
    }
    else { agentId = await createAgent(payload) }
    await replaceAgentKnowledge(agentId!, (knowledgeBaseIds ?? []).map((knowledgeBaseId, priority) => ({
      knowledgeBaseId, topK: 5, priority,
    })))
    if(workflowId)await replaceAgentWorkflow(agentId!,workflowId);else await clearAgentWorkflow(agentId!)
    const byServer=new Map<string,string[]>();(mcpToolKeys??[]).forEach(key=>{const [serverId,toolName]=key.split('::');byServer.set(serverId,[...(byServer.get(serverId)??[]),toolName])});await replaceAgentMcp(agentId!,[...byServer].map(([serverId,toolNames])=>({serverId,toolNames})))
    notifySuccess(editingId.value ? 'Agent 草稿已更新' : 'Agent 草稿已创建')
    done(); await tableRef.value?.refresh(true)
  } catch { done(false) }
}
async function publish(row: Agent) {
  publishingId.value = row.id
  try {
    const version = await publishAgent(row.id)
    notifySuccess(`Agent v${version.versionNo} 已发布`)
    await tableRef.value?.refresh()
  } finally { publishingId.value = undefined }
}
async function archiveRow(row: Agent) {
  archiveTarget.value = row
  try {
    if (await confirmArchive(row.id)) await tableRef.value?.refresh(true)
  } finally { archiveTarget.value = undefined }
}
function formatDate(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  }).format(new Date(value))
}
</script>

<template>
  <section class="page">
    <div class="page-heading">
      <div class="page-heading-copy"><span class="eyebrow">AGENT RELEASES</span><h1>Agent 管理</h1><p>编辑草稿、校验模型绑定，并发布不可变运行快照。</p></div>
      <el-button type="primary" class="primary-gradient" :icon="Plus" @click="openCreate">新增 Agent</el-button>
    </div>
    <div class="page-card">
      <HifyTable ref="tableRef" :columns="columns" :api="loadAgents">
        <template #tools="{ row }"><span class="tool-count">{{ row.enabledTools.length }}</span></template>
        <template #knowledge="{ row }"><span class="tool-count">{{ row.knowledgeBindings.length }}</span></template>
        <template #capabilities="{ row }"><span class="tool-count">{{ (row.workflowBinding?1:0)+row.mcpTools.length }}</span></template>
        <template #draft="{ row }"><span class="revision">r{{ row.draftRevision }}</span></template>
        <template #published="{ row }"><el-tag :type="!row.publishedVersionNo || row.hasUnpublishedChanges ? 'warning' : 'success'" effect="light" round>{{ !row.publishedVersionNo ? '未发布' : row.hasUnpublishedChanges ? `v${row.publishedVersionNo} · 有变更` : `v${row.publishedVersionNo} · 已同步` }}</el-tag></template>
        <template #status="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'" effect="light" round>{{ row.enabled ? '启用' : '禁用' }}</el-tag></template>
        <template #createdAt="{ row }"><span class="created-at">{{ formatDate(row.createdAt) }}</span></template>
        <template #actions="{ row }"><div class="row-actions">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="success" :icon="Promotion" :disabled="!row.hasUnpublishedChanges" :loading="publishingId === row.id" @click="publish(row)">发布</el-button>
          <el-button link type="danger" :icon="Delete" :loading="archiving && archiveTarget?.id === row.id" @click="archiveRow(row)">归档</el-button>
        </div></template>
      </HifyTable>
    </div>

    <HifyFormDialog ref="dialogRef" v-model="dialogVisible" :title="dialogTitle" :rules="rules" :initial-value="emptyForm" :width="680" @submit="save">
      <template #default="{ model }">
        <el-form-item label="名称" prop="name"><el-input v-model="model.name" placeholder="例如：研发助手" /></el-form-item>
        <el-form-item label="描述"><el-input v-model="model.description" placeholder="这个 Agent 解决什么问题" /></el-form-item>
        <el-form-item label="系统指令" prop="instructions"><el-input v-model="model.instructions" type="textarea" :rows="6" placeholder="定义角色、边界与输出要求" /></el-form-item>
        <el-form-item label="Provider" prop="providerId"><el-select v-model="model.providerId" style="width:100%" @change="onProviderChanged(model)"><el-option v-for="provider in providers" :key="provider.id" :label="provider.name" :value="provider.id" /></el-select></el-form-item>
        <el-form-item label="模型" prop="modelId"><el-select v-model="model.modelId" style="width:100%"><el-option v-for="item in selectedProvider(model)?.models.filter(m => m.enabled) ?? []" :key="item.modelId" :label="item.displayName" :value="item.modelId"><span>{{ item.displayName }}</span><small>{{ item.modelId }}</small></el-option></el-select></el-form-item>
        <div class="parameter-grid">
          <el-form-item label="Temperature"><el-input-number v-model="model.temperature" :min="0" :max="1" :step="0.1" /></el-form-item>
          <el-form-item label="最大 Token"><el-input-number v-model="model.maxTokens" :min="1" :max="32768" /></el-form-item>
          <el-form-item label="最大轮次"><el-input-number v-model="model.maxTurns" :min="1" :max="20" /></el-form-item>
          <el-form-item label="上下文轮次"><el-input-number v-model="model.maxContextTurns" :min="1" :max="100" /></el-form-item>
        </div>
        <el-form-item label="工具">
          <el-checkbox-group v-model="model.enabledTools" class="tool-options">
            <el-checkbox v-for="tool in toolCatalog" :key="tool.id" :value="tool.id" :disabled="!tool.available" class="tool-option">
              <span>{{ tool.displayName }}</span><el-tag size="small" effect="plain">{{ tool.risk }}</el-tag>
              <small>{{ tool.description }}</small>
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="知识库">
          <el-select v-model="model.knowledgeBaseIds" multiple clearable collapse-tags style="width:100%" placeholder="可选；发布时冻结当前语料版本">
            <el-option v-for="base in knowledgeBases" :key="base.id" :label="base.name" :value="base.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="入口 Workflow">
          <el-select v-model="model.workflowId" clearable style="width:100%" placeholder="可选；发布时冻结 WorkflowVersion">
            <el-option v-for="workflow in workflows" :key="workflow.id" :label="workflow.name" :value="workflow.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="MCP READ 工具">
          <el-select v-model="model.mcpToolKeys" multiple clearable collapse-tags style="width:100%" placeholder="可选；发布时冻结 revision 与 schema">
            <el-option v-for="tool in mcpTools" :key="`${tool.serverId}::${tool.name}`" :label="`${tool.serverName} / ${tool.name}`" :value="`${tool.serverId}::${tool.name}`" />
          </el-select>
        </el-form-item>
        <el-form-item label="启用"><el-switch v-model="model.enabled" /></el-form-item>
        <div class="publish-note">保存只更新草稿；点击列表中的“发布”后，新会话才会使用新版本，旧会话保持原快照。</div>
      </template>
    </HifyFormDialog>
  </section>
</template>

<style scoped>
.revision { color: var(--color-primary-700); font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-weight: 700; }
.tool-count { display: inline-grid; min-width: 26px; height: 26px; place-items: center; color: var(--color-primary-700); font-size: 12px; font-weight: 700; background: var(--color-primary-50); border-radius: 999px; }
.created-at { color: var(--color-text-secondary); font-size: 12px; }
.row-actions { display: flex; justify-content: flex-end; gap: 8px; }
.row-actions .el-button + .el-button { margin-left: 0; }
.parameter-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0 12px; }
.parameter-grid :deep(.el-input-number) { width: 100%; }
.publish-note { padding: 10px 12px; color: var(--color-text-secondary); font-size: 12px; line-height: 1.6; background: var(--color-primary-50); border-radius: var(--radius-md); }
.tool-options { display: grid; width: 100%; gap: 8px; }
.tool-option { width: 100%; height: auto; margin-right: 0; padding: 10px 12px; border: 1px solid var(--color-border); border-radius: var(--radius-md); }
.tool-option :deep(.el-checkbox__label) { display: grid; grid-template-columns: auto 1fr; align-items: center; width: 100%; gap: 2px 8px; }
.tool-option small { grid-column: 1 / -1; overflow: hidden; color: var(--color-text-tertiary); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.el-select-dropdown__item small { float: right; color: var(--color-text-tertiary); }
@media (max-width: 760px) { .parameter-grid { grid-template-columns: 1fr; } }
</style>
