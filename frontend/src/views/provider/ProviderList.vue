<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import type { FormRules } from 'element-plus'
import HifyFormDialog from '@/components/HifyFormDialog.vue'
import HifyTable, { type HifyColumn, type HifyPageResult } from '@/components/HifyTable.vue'
import { useConfirm } from '@/composables/useConfirm'
import {
  createProvider, deleteProvider, getProvider, listProviders, testProvider, updateProvider,
  type Provider, type ProviderModel, type ProviderPayload, type ProviderType,
} from '@/api/providers'
import { getHealth } from '@/api/health'
import { notifyError, notifySuccess } from '@/utils/notify'

interface ProviderForm extends Record<string, unknown> {
  name: string
  type: ProviderType
  baseUrl: string
  credentialRef: string
  headerName: string
  prefix: string
  enabled: boolean
  models: ProviderModel[]
}

const typeLabels: Record<ProviderType, string> = {
  OPENAI: 'OpenAI', ANTHROPIC: 'Anthropic', GEMINI: 'Gemini', OPENAI_COMPATIBLE: 'OpenAI-compatible',
}
const defaultUrls: Partial<Record<ProviderType, string>> = {
  OPENAI: 'https://api.openai.com/v1',
  ANTHROPIC: 'https://api.anthropic.com',
  GEMINI: 'https://generativelanguage.googleapis.com/v1beta',
}
const authDefaults: Record<ProviderType, { headerName: string; prefix: string }> = {
  OPENAI: { headerName: 'Authorization', prefix: 'Bearer ' },
  ANTHROPIC: { headerName: 'x-api-key', prefix: '' },
  GEMINI: { headerName: 'x-goog-api-key', prefix: '' },
  OPENAI_COMPATIBLE: { headerName: 'Authorization', prefix: 'Bearer ' },
}
const allColumns: HifyColumn<Provider>[] = [
  { label: '名称', prop: 'name', minWidth: 170 },
  { label: '类型', prop: 'type', width: 155, slot: 'type' },
  { label: '默认模型', prop: 'defaultModelId', minWidth: 170 },
  { label: 'Base URL', prop: 'baseUrl', minWidth: 245, className: 'responsive-optional' },
  { label: '健康状态', width: 125, slot: 'health' },
  { label: '状态', width: 90, slot: 'status' },
  { label: '操作', width: 190, slot: 'actions', align: 'right' },
]
const compactTable = ref(false)
const columns = computed(() => compactTable.value
  ? allColumns.filter(column => column.className !== 'responsive-optional') : allColumns)

type HifyTableExpose = { refresh: (resetPage?: boolean) => Promise<void> }
type HifyDialogExpose = { open: (data?: Partial<ProviderForm>) => Promise<void> }
const tableRef = ref<HifyTableExpose>()
const dialogRef = ref<HifyDialogExpose>()
const dialogVisible = ref(false)
const editingId = ref<string>()
const editingType = ref<ProviderType>()
const connected = ref(false)
const healthText = ref('检查中')
const testingId = ref<string>()
const dialogTitle = computed(() => editingId.value ? '编辑提供商' : '新增提供商')
const formRules = computed<FormRules<ProviderForm>>(() => ({
  name: [{ required: true, message: '请输入提供商名称', trigger: 'blur' }],
  type: [{ required: true, message: '请选择模型类型', trigger: 'change' }],
  credentialRef: editingId.value ? [] : [{ required: true, message: '请输入凭证引用', trigger: 'blur' }],
  baseUrl: [{ type: 'url', message: '请输入完整的 http(s) URL', trigger: 'blur' }],
}))
const { execute: confirmDelete } = useConfirm<[string]>(
  '删除后该配置将不能被新的运行使用，确定继续吗？', deleteProvider, '提供商已删除')

let tableMedia: MediaQueryList | undefined
function syncTableViewport(event: MediaQueryList | MediaQueryListEvent) { compactTable.value = event.matches }
onMounted(async () => {
  tableMedia = window.matchMedia('(max-width: 1199px)')
  syncTableViewport(tableMedia)
  tableMedia.addEventListener('change', syncTableViewport)
  try { healthText.value = await getHealth(); connected.value = true }
  catch { healthText.value = '后端未连接' }
})
onBeforeUnmount(() => tableMedia?.removeEventListener('change', syncTableViewport))

function emptyModel(): ProviderModel {
  return { displayName: '', modelId: '', enabled: true, isDefault: true }
}
function emptyForm(): ProviderForm {
  return { name: '', type: 'OPENAI', baseUrl: defaultUrls.OPENAI ?? '', credentialRef: '',
    ...authDefaults.OPENAI, enabled: true, models: [emptyModel()] }
}
function loadProviders(params: { page: number; pageSize: number }): Promise<HifyPageResult<Provider>> {
  return listProviders(params)
}
function openCreate() { editingId.value = undefined; editingType.value = undefined; void dialogRef.value?.open() }
async function openEdit(row: Provider) {
  editingId.value = row.id
  editingType.value = row.type
  const provider = await getProvider(row.id)
  await dialogRef.value?.open({
    name: provider.name, type: provider.type, baseUrl: provider.baseUrl, credentialRef: '',
    headerName: '', prefix: '', enabled: provider.enabled,
    models: provider.models.map(model => ({ ...model })),
  })
}
function applyTypeDefault(form: ProviderForm) {
  if (!form.baseUrl || Object.values(defaultUrls).includes(form.baseUrl)) form.baseUrl = defaultUrls[form.type] ?? ''
  Object.assign(form, authDefaults[form.type])
}
function addModel(form: ProviderForm) { form.models.push({ ...emptyModel(), isDefault: form.models.length === 0 }) }
function removeModel(form: ProviderForm, index: number) {
  if (form.models.length === 1) return
  const removedDefault = form.models[index]?.isDefault
  form.models.splice(index, 1)
  if (removedDefault && form.models[0]) form.models[0].isDefault = true
}
function makeDefault(form: ProviderForm, index: number) {
  form.models.forEach((model, current) => { model.isDefault = current === index; if (current === index) model.enabled = true })
}
function validateModels(form: ProviderForm): string | undefined {
  if (!form.models.length) return '至少配置一个模型'
  if (form.models.some(model => !model.displayName.trim() || !model.modelId.trim())) return '模型展示名和调用 ID 不能为空'
  if (new Set(form.models.map(model => model.modelId.trim())).size !== form.models.length) return '模型调用 ID 不能重复'
  if (form.models.filter(model => model.isDefault).length !== 1) return '必须且只能设置一个默认模型'
}
async function saveProvider(form: ProviderForm, done: (success?: boolean) => void) {
  const modelError = validateModels(form)
  if (modelError) { notifyError(modelError); done(false); return }
  if (editingId.value && editingType.value !== form.type && !form.credentialRef.trim()) {
    notifyError('切换提供商类型时必须重新填写凭证引用')
    done(false)
    return
  }
  const payload: ProviderPayload = {
    name: form.name, type: form.type, baseUrl: form.baseUrl || undefined, enabled: form.enabled,
    models: form.models.map(model => ({ ...model, displayName: model.displayName.trim(), modelId: model.modelId.trim() })),
  }
  if (form.credentialRef.trim()) payload.auth = {
    credentialRef: form.credentialRef.trim(),
    headerName: form.headerName.trim(), prefix: form.prefix,
  }
  try {
    if (editingId.value) { await updateProvider(editingId.value, payload); notifySuccess('提供商已更新') }
    else { await createProvider(payload); notifySuccess('提供商已创建') }
    done(); await tableRef.value?.refresh(true)
  } catch { done(false) }
}
async function removeProvider(id: string) { if (await confirmDelete(id)) await tableRef.value?.refresh(true) }
async function runConnectionTest(id: string) {
  testingId.value = id
  try {
    const result = await testProvider(id)
    result.success ? notifySuccess(`连接成功（${result.latencyMs} ms）`) : notifyError(result.message)
    await tableRef.value?.refresh()
  } finally { testingId.value = undefined }
}
</script>

<template>
  <section class="page provider-page">
    <div class="page-heading">
      <div class="page-heading-copy"><span class="eyebrow">MODEL PROVIDERS</span><h1>模型提供商管理</h1><p>统一维护协议、凭证引用、模型目录和独立健康状态。</p></div>
      <div class="page-actions">
        <span class="connection-pill" :title="healthText"><i class="connection-dot" :class="{ 'is-offline': !connected }" />{{ connected ? '后端已连接' : healthText }}</span>
        <el-button type="primary" class="primary-gradient" :icon="Plus" @click="openCreate">新增提供商</el-button>
      </div>
    </div>
    <div class="page-card">
      <HifyTable ref="tableRef" class="provider-table" :columns="columns" :api="loadProviders">
        <template #type="{ row }"><span class="provider-type">{{ typeLabels[row.type] }}</span></template>
        <template #health="{ row }">
          <el-tag :type="row.health.status === 'HEALTHY' ? 'success' : row.health.status === 'UNHEALTHY' ? 'danger' : 'info'" effect="light" round>
            {{ row.health.status === 'HEALTHY' ? '健康' : row.health.status === 'UNHEALTHY' ? '异常' : '未检测' }}
          </el-tag>
        </template>
        <template #status="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'" effect="light" round>{{ row.enabled ? '启用' : '禁用' }}</el-tag></template>
        <template #actions="{ row }"><div class="row-actions">
          <el-button link type="success" :loading="testingId === row.id" @click="runConnectionTest(row.id)">测试</el-button>
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="removeProvider(row.id)">删除</el-button>
        </div></template>
      </HifyTable>
    </div>

    <HifyFormDialog ref="dialogRef" v-model="dialogVisible" :title="dialogTitle" :rules="formRules" :initial-value="emptyForm" :width="720" @submit="saveProvider">
      <template #default="{ model }">
        <el-form-item label="名称" prop="name"><el-input v-model="model.name" placeholder="例如：OpenAI Production" /></el-form-item>
        <el-form-item label="类型" prop="type"><el-select v-model="model.type" style="width: 100%" @change="applyTypeDefault(model)"><el-option v-for="(label, value) in typeLabels" :key="value" :label="label" :value="value" /></el-select></el-form-item>
        <el-form-item label="Base URL" prop="baseUrl"><el-input v-model="model.baseUrl" :placeholder="defaultUrls[model.type] ?? 'https://llm.example.com/v1'" /></el-form-item>
        <el-form-item label="凭证引用" prop="credentialRef"><el-input v-model="model.credentialRef" :placeholder="editingId ? '留空表示不修改' : 'env:OPENAI_API_KEY'" /><div class="form-help">只保存 env:/Vault 等引用，不保存 API Key 明文。</div></el-form-item>
        <el-collapse class="advanced-auth"><el-collapse-item title="高级鉴权（通用兼容）"><el-form-item label="Header"><el-input v-model="model.headerName" placeholder="Authorization" /></el-form-item><el-form-item label="前缀"><el-input v-model="model.prefix" placeholder="Bearer " /></el-form-item></el-collapse-item></el-collapse>
        <el-form-item label="启用"><el-switch v-model="model.enabled" /></el-form-item>
        <div class="model-heading"><strong>模型目录</strong><el-button link type="primary" :icon="Plus" @click="addModel(model)">添加模型</el-button></div>
        <div v-for="(item, index) in model.models" :key="index" class="model-row">
          <el-input v-model="item.displayName" placeholder="展示名，如 GPT-5 Mini" />
          <el-input v-model="item.modelId" placeholder="调用 ID，如 gpt-5-mini" />
          <el-checkbox v-model="item.enabled">启用</el-checkbox>
          <el-radio :model-value="item.isDefault ? index : -1" :value="index" @change="makeDefault(model, index)">默认</el-radio>
          <el-button link type="danger" :disabled="model.models.length === 1" @click="removeModel(model, index)">移除</el-button>
        </div>
      </template>
    </HifyFormDialog>
  </section>
</template>

<style scoped>
.provider-type { display: inline-flex; align-items: center; height: 26px; padding: 0 9px; color: var(--color-primary-700); font-size: 12px; font-weight: 700; background: var(--color-primary-50); border: 1px solid var(--color-primary-100); border-radius: var(--radius-pill); }
.row-actions { display: flex; align-items: center; justify-content: flex-end; gap: 8px; }
.row-actions .el-button + .el-button { margin-left: 0; }
.form-help { margin-top: 5px; color: var(--color-text-tertiary); font-size: 12px; }
.advanced-auth { margin: -6px 0 16px 100px; border-top: 0; }
.model-heading { display: flex; align-items: center; justify-content: space-between; margin: 8px 0 10px; }
.model-row { display: grid; grid-template-columns: 1fr 1.2fr auto auto auto; gap: 8px; align-items: center; margin-bottom: 8px; }
@media (max-width: 760px) { .model-row { grid-template-columns: 1fr; padding-bottom: 12px; border-bottom: 1px solid var(--color-divider); } .advanced-auth { margin-left: 0; } }
</style>
