<script setup lang="ts">
import { ref } from 'vue'
import { Connection, Delete, Edit, Plus, Refresh } from '@element-plus/icons-vue'
import type { FormRules } from 'element-plus'
import { ElMessageBox } from 'element-plus'
import HifyTable, { type HifyColumn, type HifyPageResult } from '@/components/HifyTable.vue'
import HifyFormDialog from '@/components/HifyFormDialog.vue'
import {
  archiveMcpServer, callMcpTool, createMcpServer, updateMcpServer,
  listMcpServers, listMcpTools, refreshMcpTools, type McpServer, type McpTool,
} from '@/api/mcp'
import { notifySuccess } from '@/utils/notify'

interface Form extends Record<string, unknown> {
  name: string
  endpointUrl: string
  credentialRef: string
  enabled: boolean
}
type TableExpose = { refresh: (reset?: boolean) => Promise<void> }
type DialogExpose = { open: (data?: Partial<Form>) => Promise<void> }
const table = ref<TableExpose>()
const dialog = ref<DialogExpose>()
const visible = ref(false)
const editingId = ref<string>()
const current = ref<McpServer>()
const tools = ref<McpTool[]>([])
const toolsVisible = ref(false)
const selectedTool = ref('')
const argumentsJson = ref('{}')
const resultJson = ref('')
const busy = ref('')
const columns: HifyColumn<McpServer>[] = [
  { label: '服务', prop: 'name', minWidth: 160 },
  { label: 'Endpoint', prop: 'endpointUrl', minWidth: 260 },
  { label: '状态', width: 150, slot: 'status' },
  { label: 'Revision', prop: 'serverRevision', width: 90 },
  { label: 'Digest', width: 130, slot: 'digest' },
  { label: '操作', width: 330, slot: 'actions', align: 'right', fixed: 'right' },
]
const credentialPattern = /^(?:env:[A-Za-z_][A-Za-z0-9_]*|system:[A-Za-z_][A-Za-z0-9_.-]*)$/
const rules: FormRules<Form> = {
  name: [{ required: true, whitespace: true, message: '请输入名称', trigger: 'blur' }],
  endpointUrl: [{ required: true, message: '请输入 MCP Endpoint', trigger: 'blur' }, {
    validator: (_rule, value: string, callback) => {
      try {
        const url = new URL(value.trim())
        if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) throw new Error()
        callback()
      } catch { callback(new Error('请输入不含用户名密码的 HTTP(S) 地址')) }
    }, trigger: 'blur',
  }],
  credentialRef: [{
    validator: (_rule, value: string, callback) => {
      const ref = (value ?? '').trim()
      callback(!ref || credentialPattern.test(ref) ? undefined
        : new Error('仅支持 env:变量名 或 system:属性名，请勿填写 Token 明文'))
    }, trigger: 'blur',
  }],
}
const empty = (): Form => ({ name: '', endpointUrl: '', credentialRef: '', enabled: true })
async function load(): Promise<HifyPageResult<McpServer>> {
  const data = await listMcpServers()
  return { data, total: data.length, page: 1, size: 100 }
}
async function openCreate() {
  editingId.value = undefined
  await dialog.value?.open()
}
async function openEdit(row: McpServer) {
  editingId.value = row.id
  await dialog.value?.open({
    name: row.name, endpointUrl: row.endpointUrl,
    credentialRef: row.credentialRef ?? '', enabled: row.enabled,
  })
}
async function save(form: Form, done: (ok?: boolean) => void) {
  const id = editingId.value
  const payload = {
    name: form.name.trim(), endpointUrl: form.endpointUrl.trim(),
    credentialRef: form.credentialRef.trim(), enabled: form.enabled,
  }
  try {
    if (id) await updateMcpServer(id, payload)
    else await createMcpServer(payload)
  } catch { done(false); return }
  notifySuccess(id ? 'MCP Server 已更新；连接配置变更后请重新发现工具' : 'MCP Server 已创建')
  done()
  await table.value?.refresh(!id)
}
async function refresh(row: McpServer) {
  busy.value = row.id
  try {
    const found = await refreshMcpTools(row.id)
    notifySuccess('发现 ' + found.length + ' 个工具，revision 已更新')
    await table.value?.refresh()
  } finally { busy.value = '' }
}
async function openTools(row: McpServer) {
  current.value = row
  tools.value = await listMcpTools(row.id)
  selectedTool.value = tools.value.find(t => t.risk === 'READ')?.name ?? ''
  argumentsJson.value = '{}'
  resultJson.value = ''
  toolsVisible.value = true
}
async function debug() {
  if (!current.value || !selectedTool.value) return
  let args: Record<string, unknown>
  try { args = JSON.parse(argumentsJson.value) }
  catch { await ElMessageBox.alert('参数必须是有效 JSON 对象'); return }
  const result = await callMcpTool(current.value.id, selectedTool.value, args)
  resultJson.value = JSON.stringify(result, null, 2)
}
async function remove(row: McpServer) {
  await ElMessageBox.confirm('确认归档「' + row.name + '」？', '归档 MCP Server', { type: 'warning' })
  await archiveMcpServer(row.id)
  await table.value?.refresh(true)
}
</script>

<template>
  <section class="page">
    <div class="page-heading">
      <div class="page-heading-copy">
        <span class="eyebrow">MODEL CONTEXT PROTOCOL</span>
        <h1>MCP Servers</h1>
        <p>发现并冻结工具 schema；控制台只允许调试标注为 READ 的工具。</p>
      </div>
      <el-button type="primary" class="primary-gradient" :icon="Plus" @click="openCreate">添加 Server</el-button>
    </div>
    <div class="page-card">
      <HifyTable ref="table" :columns="columns" :api="load" :show-pagination="false">
        <template #status="{ row }">
          <el-tag :type="!row.enabled ? 'info' : row.status === 'READY' ? 'success' : row.status === 'FAILED' ? 'danger' : 'info'">
            {{ row.enabled ? row.status : '已停用' }}
          </el-tag>
          <span v-if="row.enabled && row.status === 'NEW'" class="pending">待发现</span>
        </template>
        <template #digest="{ row }"><code>{{ row.schemaDigest?.slice(0, 12) ?? '—' }}</code></template>
        <template #actions="{ row }">
          <div class="row-actions">
            <el-button link type="primary" :icon="Edit" :disabled="busy === row.id" @click="openEdit(row)">编辑</el-button>
            <el-button link type="success" :icon="Refresh" :loading="busy === row.id" :disabled="!row.enabled" @click="refresh(row)">发现工具</el-button>
            <el-button link type="primary" :icon="Connection" :disabled="!row.enabled || row.status !== 'READY'" @click="openTools(row)">工具调试</el-button>
            <el-button link type="danger" :icon="Delete" @click="remove(row)">归档</el-button>
          </div>
        </template>
      </HifyTable>
    </div>
    <HifyFormDialog ref="dialog" v-model="visible" :title="editingId ? '编辑 MCP Server' : '添加 MCP Server'" :rules="rules" :initial-value="empty" @submit="save">
      <template #default="{ model }">
        <el-form-item label="名称" prop="name"><el-input v-model="model.name" maxlength="160" /></el-form-item>
        <el-form-item label="Endpoint" prop="endpointUrl"><el-input v-model="model.endpointUrl" maxlength="1000" placeholder="https://mcp.example.com/mcp" /></el-form-item>
        <el-form-item label="凭证引用" prop="credentialRef">
          <el-input v-model="model.credentialRef" maxlength="255" type="password" show-password autocomplete="off" placeholder="env:MCP_TOKEN" />
          <p class="field-help">无鉴权可留空。有鉴权请填 env:MCP_TOKEN 或 system:mcp.token，并在后端配置对应值；此处不要粘贴 Token。</p>
        </el-form-item>
        <el-form-item label="启用"><el-switch v-model="model.enabled" /></el-form-item>
        <div class="warning">
          <template v-if="editingId">修改地址或凭证引用后，需重新「发现工具」，再到 Agent 发布新版本；旧会话继续使用原有能力快照。<br /></template>
          生产默认拒绝 loopback、私网和 link-local 地址，并关闭 HTTP 重定向。
        </div>
      </template>
    </HifyFormDialog>
    <el-drawer v-model="toolsVisible" :title="(current?.name ?? '') + ' · Tool Inspector'" size="680px">
      <el-table :data="tools" empty-text="请先执行工具发现">
        <el-table-column prop="name" label="工具" min-width="160" />
        <el-table-column prop="description" label="说明" min-width="220" />
        <el-table-column prop="risk" label="风险" width="100"><template #default="{ row }"><el-tag :type="row.risk === 'READ' ? 'success' : 'warning'">{{ row.risk }}</el-tag></template></el-table-column>
      </el-table>
      <div class="debug">
        <h3>只读调用</h3>
        <el-select v-model="selectedTool" placeholder="选择 READ 工具" style="width: 100%"><el-option v-for="tool in tools.filter(t => t.risk === 'READ')" :key="tool.name" :label="tool.name" :value="tool.name" /></el-select>
        <el-input v-model="argumentsJson" type="textarea" :rows="6" />
        <el-button type="primary" :disabled="!selectedTool" @click="debug">执行 tools/call</el-button>
        <pre v-if="resultJson">{{ resultJson }}</pre>
      </div>
    </el-drawer>
  </section>
</template>

<style scoped>
.row-actions { display: flex; justify-content: flex-end; gap: 8px; }
.row-actions .el-button + .el-button { margin: 0; }
code { font-size: 11px; color: var(--color-text-tertiary); }
.pending { margin-left: 6px; color: var(--color-text-tertiary); font-size: 12px; }
.field-help { margin: 6px 0 0; color: var(--color-text-tertiary); font-size: 12px; line-height: 1.6; }
.warning { padding: 10px 12px; color: var(--color-warning); font-size: 12px; line-height: 1.8; background: #fff8e8; border-radius: var(--radius-md); }
.debug { display: grid; gap: 12px; margin-top: 24px; }
.debug h3 { margin: 0; }
.debug pre { overflow: auto; padding: 14px; white-space: pre-wrap; background: #10131a; color: #c7f9ef; border-radius: var(--radius-md); }
</style>
