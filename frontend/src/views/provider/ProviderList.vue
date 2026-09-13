<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import type { FormRules } from 'element-plus'
import HifyFormDialog from '@/components/HifyFormDialog.vue'
import HifyTable, { type HifyColumn, type HifyPageResult } from '@/components/HifyTable.vue'
import { useConfirm } from '@/composables/useConfirm'
import { getHealth } from '@/api/health'
import { notifySuccess } from '@/utils/notify'

type ProviderType = 'OpenAI' | 'Claude' | 'Gemini' | 'Ollama'
type ProviderStatus = 'enabled' | 'disabled'

interface Provider extends Record<string, unknown> {
  id: number
  name: string
  type: ProviderType
  baseUrl: string
  status: ProviderStatus
  createdAt: string
}

interface ProviderForm extends Record<string, unknown> {
  name: string
  type: ProviderType | ''
  apiKey: string
  baseUrl: string
}

const providers = ref<Provider[]>([
  { id: 1, name: 'OpenAI Production', type: 'OpenAI', baseUrl: 'https://api.openai.com/v1', status: 'enabled', createdAt: '2026-09-01T09:30:00' },
  { id: 2, name: 'Claude Team', type: 'Claude', baseUrl: 'https://api.anthropic.com', status: 'enabled', createdAt: '2026-09-02T11:15:00' },
  { id: 3, name: 'Gemini Sandbox', type: 'Gemini', baseUrl: 'https://generativelanguage.googleapis.com', status: 'disabled', createdAt: '2026-09-04T14:20:00' },
  { id: 4, name: 'Ollama Local', type: 'Ollama', baseUrl: 'http://localhost:11434', status: 'enabled', createdAt: '2026-09-06T16:45:00' },
  { id: 5, name: 'OpenAI Staging', type: 'OpenAI', baseUrl: 'https://staging.example.com/v1', status: 'disabled', createdAt: '2026-09-08T10:05:00' },
])

const allColumns: HifyColumn<Provider>[] = [
  { label: '名称', prop: 'name', minWidth: 180 },
  { label: '类型', prop: 'type', width: 120, slot: 'type' },
  { label: 'Base URL', prop: 'baseUrl', minWidth: 240, className: 'responsive-optional' },
  { label: '状态', prop: 'status', width: 110, slot: 'status' },
  { label: '创建时间', prop: 'createdAt', width: 180, className: 'responsive-optional' },
  { label: '操作', width: 130, slot: 'actions', align: 'right' },
]
const compactTable = ref(false)
const columns = computed(() => compactTable.value
  ? allColumns.filter(column => column.className !== 'responsive-optional')
  : allColumns)

type HifyTableExpose = { refresh: (resetPage?: boolean) => Promise<void> }
type HifyDialogExpose = { open: (data?: Partial<ProviderForm>) => Promise<void> }

const tableRef = ref<HifyTableExpose>()
const dialogRef = ref<HifyDialogExpose>()
const dialogVisible = ref(false)
const editingId = ref<number>()
const connected = ref(false)
const healthText = ref('检查中')
const dialogTitle = computed(() => editingId.value ? '编辑提供商' : '新增提供商')
const formRules = computed<FormRules<ProviderForm>>(() => ({
  name: [{ required: true, message: '请输入提供商名称', trigger: 'blur' }],
  type: [{ required: true, message: '请选择模型类型', trigger: 'change' }],
  apiKey: editingId.value ? [] : [{ required: true, message: '请输入 API Key', trigger: 'blur' }],
  baseUrl: [
    { required: true, message: '请输入 Base URL', trigger: 'blur' },
    { type: 'url', message: '请输入完整的 http(s) URL', trigger: 'blur' },
  ],
}))

const { execute: confirmDelete } = useConfirm<[number]>(
  '删除后该配置将无法用于新的 Agent，确定继续吗？',
  async id => { providers.value = providers.value.filter(item => item.id !== id) },
  '提供商已删除',
)

let tableMedia: MediaQueryList | undefined

function syncTableViewport(event: MediaQueryList | MediaQueryListEvent) {
  compactTable.value = event.matches
}

onMounted(async () => {
  tableMedia = window.matchMedia('(max-width: 1199px)')
  syncTableViewport(tableMedia)
  tableMedia.addEventListener('change', syncTableViewport)
  try {
    healthText.value = await getHealth()
    connected.value = true
  } catch {
    healthText.value = '后端未连接'
  }
})

onBeforeUnmount(() => tableMedia?.removeEventListener('change', syncTableViewport))

async function loadProviders(params: { page: number; pageSize: number }): Promise<HifyPageResult<Provider>> {
  await new Promise(resolve => window.setTimeout(resolve, 120))
  const start = (params.page - 1) * params.pageSize
  return { data: providers.value.slice(start, start + params.pageSize), total: providers.value.length, page: params.page, size: params.pageSize }
}

function emptyForm(): ProviderForm {
  return { name: '', type: '', apiKey: '', baseUrl: '' }
}

function openCreate() {
  editingId.value = undefined
  void dialogRef.value?.open()
}

function openEdit(provider: Provider) {
  editingId.value = provider.id
  void dialogRef.value?.open({ name: provider.name, type: provider.type, apiKey: '', baseUrl: provider.baseUrl })
}

function saveProvider(form: ProviderForm, done: (success?: boolean) => void) {
  window.setTimeout(() => {
    if (editingId.value) {
      const target = providers.value.find(item => item.id === editingId.value)
      if (target) Object.assign(target, { name: form.name, type: form.type, baseUrl: form.baseUrl })
      notifySuccess('提供商已更新')
    } else {
      providers.value.unshift({
        id: Math.max(0, ...providers.value.map(item => item.id)) + 1,
        name: form.name,
        type: form.type as ProviderType,
        baseUrl: form.baseUrl,
        status: 'enabled',
        createdAt: new Date().toISOString().slice(0, 19),
      })
      notifySuccess('提供商已创建')
    }
    done()
    void tableRef.value?.refresh(true)
  }, 260)
}

async function removeProvider(id: number) {
  if (await confirmDelete(id)) await tableRef.value?.refresh(true)
}
</script>

<template>
  <section class="page provider-page">
    <div class="page-heading">
      <div class="page-heading-copy">
        <span class="eyebrow">MODEL PROVIDERS</span>
        <h1>模型提供商管理</h1>
        <p>统一配置模型服务、访问地址与可用状态。</p>
      </div>
      <div class="page-actions">
        <span class="connection-pill" :title="healthText">
          <i class="connection-dot" :class="{ 'is-offline': !connected }" />
          {{ connected ? '后端已连接' : healthText }}
        </span>
        <el-button type="primary" class="primary-gradient" :icon="Plus" @click="openCreate">新增提供商</el-button>
      </div>
    </div>

    <div class="page-card">
      <HifyTable ref="tableRef" class="provider-table" :columns="columns" :api="loadProviders">
        <template #type="{ row }"><span class="provider-type">{{ row.type }}</span></template>
        <template #status="{ row }">
          <el-tag :type="row.status === 'enabled' ? 'success' : 'info'" effect="light" round>
            {{ row.status === 'enabled' ? '启用' : '禁用' }}
          </el-tag>
        </template>
        <template #actions="{ row }">
          <div class="row-actions">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="removeProvider(row.id)">删除</el-button>
          </div>
        </template>
      </HifyTable>
    </div>

    <HifyFormDialog
      ref="dialogRef"
      v-model="dialogVisible"
      :title="dialogTitle"
      :rules="formRules"
      :initial-value="emptyForm"
      :width="520"
      @submit="saveProvider"
    >
      <template #default="{ model }">
        <el-form-item label="名称" prop="name"><el-input v-model="model.name" placeholder="例如：OpenAI Production" /></el-form-item>
        <el-form-item label="类型" prop="type">
          <el-select v-model="model.type" placeholder="请选择模型类型" style="width: 100%">
            <el-option v-for="type in ['OpenAI', 'Claude', 'Gemini', 'Ollama']" :key="type" :label="type" :value="type" />
          </el-select>
        </el-form-item>
        <el-form-item label="API Key" prop="apiKey">
          <el-input v-model="model.apiKey" type="password" show-password :placeholder="editingId ? '留空表示不修改' : '请输入 API Key'" />
        </el-form-item>
        <el-form-item label="Base URL" prop="baseUrl"><el-input v-model="model.baseUrl" placeholder="https://api.openai.com/v1" /></el-form-item>
      </template>
    </HifyFormDialog>
  </section>
</template>

<style scoped>
.provider-type { display: inline-flex; align-items: center; height: 26px; padding: 0 9px; color: var(--color-primary-700); font-size: 12px; font-weight: 700; background: var(--color-primary-50); border: 1px solid var(--color-primary-100); border-radius: var(--radius-pill); }
.row-actions { display: flex; align-items: center; justify-content: flex-end; gap: 8px; }
.row-actions .el-button + .el-button { margin-left: 0; }
</style>
