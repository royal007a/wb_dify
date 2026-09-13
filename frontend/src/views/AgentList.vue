<script setup lang="ts">
import { computed, ref } from 'vue'
import { Plus, Promotion } from '@element-plus/icons-vue'
import type { FormRules } from 'element-plus'
import HifyFormDialog from '@/components/HifyFormDialog.vue'
import HifyTable, { type HifyColumn, type HifyPageResult } from '@/components/HifyTable.vue'
import { notifySuccess } from '@/utils/notify'
import { createAgent, getAgent, listAgents, publishAgent, updateAgent,
  type Agent, type AgentPayload } from '@/api/agents'
import { listProviders, type Provider } from '@/api/providers'

interface AgentForm extends Record<string, unknown>, AgentPayload {}
type TableExpose = { refresh: (resetPage?: boolean) => Promise<void> }
type DialogExpose = { open: (data?: Partial<AgentForm>) => Promise<void> }

const columns: HifyColumn<Agent>[] = [
  { label: '名称', prop: 'name', minWidth: 180 },
  { label: '模型', prop: 'modelId', minWidth: 170 },
  { label: '草稿', width: 90, slot: 'draft' },
  { label: '已发布', width: 110, slot: 'published' },
  { label: '状态', width: 90, slot: 'status' },
  { label: '操作', width: 170, slot: 'actions', align: 'right' },
]
const tableRef = ref<TableExpose>()
const dialogRef = ref<DialogExpose>()
const dialogVisible = ref(false)
const editingId = ref<string>()
const publishingId = ref<string>()
const providers = ref<Provider[]>([])
const dialogTitle = computed(() => editingId.value ? '编辑 Agent 草稿' : '创建 Agent 草稿')
const rules: FormRules<AgentForm> = {
  name: [{ required: true, message: '请输入 Agent 名称', trigger: 'blur' }],
  instructions: [{ required: true, message: '请输入系统指令', trigger: 'blur' }],
  providerId: [{ required: true, message: '请选择 Provider', trigger: 'change' }],
  modelId: [{ required: true, message: '请选择模型', trigger: 'change' }],
}
const selectedProvider = (form: AgentForm) => providers.value.find(item => item.id === form.providerId)

function emptyForm(): AgentForm {
  return { name: '', description: '', instructions: '', providerId: '', modelId: '', temperature: 0.2,
    maxTokens: 2048, maxTurns: 6, maxContextTurns: 10, enabledTools: [], enabled: true }
}
function loadAgents(params: { page: number; pageSize: number }): Promise<HifyPageResult<Agent>> {
  return listAgents(params)
}
async function ensureProviders() {
  if (providers.value.length) return
  providers.value = (await listProviders({ page: 1, pageSize: 100 })).data.filter(item => item.enabled)
}
async function openCreate() {
  editingId.value = undefined
  await ensureProviders()
  await dialogRef.value?.open()
}
async function openEdit(row: Agent) {
  editingId.value = row.id
  await ensureProviders()
  const agent = await getAgent(row.id)
  await dialogRef.value?.open({ ...agent })
}
function onProviderChanged(form: AgentForm) {
  const provider = selectedProvider(form)
  form.modelId = provider?.defaultModelId ?? ''
}
async function save(form: AgentForm, done: (success?: boolean) => void) {
  try {
    const payload: AgentPayload = { ...form, enabledTools: form.enabledTools ?? [] }
    if (editingId.value) { await updateAgent(editingId.value, payload); notifySuccess('Agent 草稿已更新') }
    else { await createAgent(payload); notifySuccess('Agent 草稿已创建') }
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
</script>

<template>
  <section class="page">
    <div class="page-heading">
      <div class="page-heading-copy"><span class="eyebrow">AGENT RELEASES</span><h1>Agent 管理</h1><p>编辑草稿、校验模型绑定，并发布不可变运行快照。</p></div>
      <el-button type="primary" class="primary-gradient" :icon="Plus" @click="openCreate">新增 Agent</el-button>
    </div>
    <div class="page-card">
      <HifyTable ref="tableRef" :columns="columns" :api="loadAgents">
        <template #draft="{ row }"><span class="revision">r{{ row.draftRevision }}</span></template>
        <template #published="{ row }"><el-tag :type="row.publishedVersionNo ? 'success' : 'warning'" effect="light" round>{{ row.publishedVersionNo ? `v${row.publishedVersionNo}` : '未发布' }}</el-tag></template>
        <template #status="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'" effect="light" round>{{ row.enabled ? '启用' : '禁用' }}</el-tag></template>
        <template #actions="{ row }"><div class="row-actions">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="success" :icon="Promotion" :loading="publishingId === row.id" @click="publish(row)">发布</el-button>
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
          <el-form-item label="Temperature"><el-input-number v-model="model.temperature" :min="0" :max="2" :step="0.1" /></el-form-item>
          <el-form-item label="最大 Token"><el-input-number v-model="model.maxTokens" :min="1" :max="32768" /></el-form-item>
          <el-form-item label="最大轮次"><el-input-number v-model="model.maxTurns" :min="1" :max="20" /></el-form-item>
          <el-form-item label="上下文轮次"><el-input-number v-model="model.maxContextTurns" :min="1" :max="100" /></el-form-item>
        </div>
        <el-form-item label="工具"><el-checkbox-group v-model="model.enabledTools"><el-checkbox value="current_time">当前时间</el-checkbox><el-checkbox value="calculator">计算器</el-checkbox></el-checkbox-group></el-form-item>
        <el-form-item label="启用"><el-switch v-model="model.enabled" /></el-form-item>
        <div class="publish-note">保存只更新草稿；点击列表中的“发布”后，新会话才会使用新版本，旧会话保持原快照。</div>
      </template>
    </HifyFormDialog>
  </section>
</template>

<style scoped>
.revision { color: var(--color-primary-700); font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-weight: 700; }
.row-actions { display: flex; justify-content: flex-end; gap: 8px; }
.row-actions .el-button + .el-button { margin-left: 0; }
.parameter-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0 12px; }
.parameter-grid :deep(.el-input-number) { width: 100%; }
.publish-note { padding: 10px 12px; color: var(--color-text-secondary); font-size: 12px; line-height: 1.6; background: var(--color-primary-50); border-radius: var(--radius-md); }
.el-select-dropdown__item small { float: right; color: var(--color-text-tertiary); }
@media (max-width: 760px) { .parameter-grid { grid-template-columns: 1fr; } }
</style>
