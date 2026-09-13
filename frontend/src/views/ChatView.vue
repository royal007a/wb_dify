<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

type Agent = { id: string; name: string; description: string; model: string; enabledTools: string }
type Run = { id: string; state: string; terminalReason?: string; outputMessage?: string; turns: number; toolCalls: number; streamUrl: string }
type ChatItem = { role: 'user' | 'assistant' | 'event'; content: string }

const agents = ref<Agent[]>([])
const selectedAgentId = ref('demo-agent')
const conversationId = ref<string>()
const message = ref('现在几点？')
const running = ref(false)
const status = ref('准备就绪')
const activeRun = ref<Run>()
const chat = ref<ChatItem[]>([{ role: 'assistant', content: '你好，我是 Hify Demo Agent。可以问我时间，或让我计算 12.5 * 4。' }])
const selectedAgent = computed(() => agents.value.find(agent => agent.id === selectedAgentId.value))

onMounted(loadAgents)

async function loadAgents() {
  const response = await fetch('/api/agents')
  if (!response.ok) throw new Error('无法加载 Agent')
  agents.value = await response.json()
}

async function ensureConversation() {
  if (conversationId.value) return conversationId.value
  const response = await fetch('/api/v1/conversations', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ agentId: selectedAgentId.value, title: 'Hify Playground' }),
  })
  if (!response.ok) throw new Error(await errorMessage(response))
  const conversation = await response.json()
  conversationId.value = conversation.id
  return conversation.id as string
}

async function send() {
  const text = message.value.trim()
  if (!text || running.value) return
  running.value = true
  status.value = '创建 Run…'
  chat.value.push({ role: 'user', content: text })
  message.value = ''
  try {
    const conversation = await ensureConversation()
    const response = await fetch(`/api/v1/conversations/${conversation}/runs`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ message: text }),
    })
    if (!response.ok) throw new Error(await errorMessage(response))
    activeRun.value = await response.json()
    status.value = `Run ${activeRun.value!.id.slice(0, 8)} 执行中`
    listen(activeRun.value!)
  } catch (error) {
    chat.value.push({ role: 'event', content: error instanceof Error ? error.message : '请求失败' })
    running.value = false
    status.value = '请求失败'
  }
}

function listen(run: Run) {
  const source = new EventSource(run.streamUrl)
  let answerAdded = false
  source.addEventListener('message.delta', event => {
    const payload = parsePayload((event as MessageEvent).data)
    if (payload.content) {
      chat.value.push({ role: 'assistant', content: String(payload.content) })
      answerAdded = true
    }
  })
  source.addEventListener('tool.call.started', event => {
    const payload = parsePayload((event as MessageEvent).data)
    chat.value.push({ role: 'event', content: `调用工具：${payload.tool}` })
  })
  const finish = async () => {
    source.close()
    const response = await fetch(`/api/v1/runs/${run.id}`)
    const latest: Run = await response.json()
    activeRun.value = latest
    if (!answerAdded && latest.outputMessage) chat.value.push({ role: latest.state === 'COMPLETED' ? 'assistant' : 'event', content: latest.outputMessage })
    status.value = `${latest.state} · ${latest.turns} turns · ${latest.toolCalls} tools`
    running.value = false
  }
  source.addEventListener('run.completed', finish)
  source.addEventListener('run.failed', finish)
  source.addEventListener('run.cancelled', finish)
}

async function cancel() {
  if (!activeRun.value || !running.value) return
  await fetch(`/api/v1/runs/${activeRun.value.id}/cancellations`, { method: 'POST' })
  status.value = '已请求取消…'
}

function newConversation() {
  conversationId.value = undefined
  activeRun.value = undefined
  chat.value = [{ role: 'assistant', content: '新会话已就绪。' }]
  status.value = '准备就绪'
}

function parsePayload(raw: string): Record<string, unknown> {
  try {
    const parsed = JSON.parse(raw)
    return typeof parsed === 'string' ? JSON.parse(parsed) : parsed
  } catch { return { content: raw } }
}

async function errorMessage(response: Response) {
  try {
    const payload = await response.json()
    return payload.message ?? `HTTP ${response.status}`
  } catch { return `HTTP ${response.status}` }
}
</script>

<template>
  <section class="chat-page">
    <div class="chat-toolbar">
      <div><span class="eyebrow">CONVERSATION</span><h1>对话</h1><p>{{ status }}</p></div>
      <div class="toolbar-actions">
        <el-select v-model="selectedAgentId" :disabled="running" @change="newConversation">
          <el-option v-for="agent in agents" :key="agent.id" :label="agent.name" :value="agent.id" />
        </el-select>
        <el-button @click="newConversation">新会话</el-button>
      </div>
    </div>
    <div class="chat-panel">
      <div class="messages">
        <div v-for="(item, index) in chat" :key="index" :class="['message', item.role]">
          <span class="avatar">{{ item.role === 'user' ? '你' : item.role === 'assistant' ? 'H' : '·' }}</span>
          <div>{{ item.content }}</div>
        </div>
      </div>
      <form class="composer" @submit.prevent="send">
        <el-input v-model="message" type="textarea" :rows="2" placeholder="输入消息；例如：计算 17 * 23" />
        <div class="composer-actions">
          <span>{{ selectedAgent?.name ?? 'Demo Agent' }}</span>
          <el-button v-if="running" type="danger" @click="cancel">取消</el-button>
          <el-button v-else type="primary" class="primary-gradient" native-type="submit" :disabled="!message.trim()">运行</el-button>
        </div>
      </form>
    </div>
  </section>
</template>
