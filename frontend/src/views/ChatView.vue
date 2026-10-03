<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import type { Agent } from '@/api/agents'
import {
  cancelRun,
  createConversation,
  createRun,
  getRun,
  getRunEvents,
  listRunnableAgents,
  runtimeUrl,
  type ResumeState,
  type Run,
} from '@/api/chat'

type ChatItem = { role: 'user' | 'assistant' | 'event'; content: string }

const agents = ref<Agent[]>([])
const selectedAgentId = ref('')
const conversationId = ref<string>()
const pinnedVersionId = ref<string>()
const message = ref('现在几点？')
const running = ref(false)
const status = ref('准备就绪')
const activeRun = ref<Run>()
const resumeState = ref<ResumeState>()
const syncIssue = ref(false)
const syncingRun = ref(false)
let stream: EventSource | undefined
let syncTimer: ReturnType<typeof setTimeout> | undefined
let retryRead: (() => Promise<void>) | undefined
let generation = 0
let disposed = false
const terminalStates = new Set(['COMPLETED', 'FAILED', 'CANCELLED', 'TIMED_OUT', 'LIMIT_EXCEEDED', 'NEEDS_INPUT'])
const welcome = '你好！请选择已发布的 Agent 开始对话。Demo Agent 是本地规则模拟，用于演示时间查询和计算；通用问答需要配置真实模型。'
const chat = ref<ChatItem[]>([{ role: 'assistant', content: welcome }])
const selectedAgent = computed(() => agents.value.find(agent => agent.id === selectedAgentId.value))

onMounted(loadAgents)
onBeforeUnmount(() => {
  disposed = true
  generation++
  closeStream()
  retryRead = undefined
})

function closeStream() {
  stream?.close()
  stream = undefined
  if (syncTimer) clearTimeout(syncTimer)
  syncTimer = undefined
}

function isCurrent(token: number) { return !disposed && generation === token }

async function loadAgents() {
  try {
    const available = await listRunnableAgents()
    if (disposed) return
    agents.value = available
    selectedAgentId.value = agents.value[0]?.id ?? ''
    if (!selectedAgentId.value) status.value = '没有可运行的已发布 Agent'
  } catch (error) {
    status.value = error instanceof Error ? error.message : '无法加载 Agent'
  }
}

async function ensureConversation() {
  if (conversationId.value) return conversationId.value
  if (!selectedAgentId.value) throw new Error('请先选择已发布 Agent')
  const conversation = await createConversation(selectedAgentId.value)
  conversationId.value = conversation.id
  pinnedVersionId.value = conversation.agentVersionId
  return conversation.id as string
}

async function send() {
  const text = message.value.trim()
  if (!text || running.value || syncIssue.value) return
  const token = ++generation
  running.value = true
  status.value = '创建 Run…'
  chat.value.push({ role: 'user', content: text })
  message.value = ''
  try {
    const conversation = await ensureConversation()
    if (!isCurrent(token)) return
    const created = await createRun(conversation, text, resumeState.value)
    if (!isCurrent(token)) return
    activeRun.value = created
    resumeState.value = undefined
    status.value = `Run ${activeRun.value!.id.slice(0, 8)} 执行中`
    listen(activeRun.value!, token)
  } catch (error) {
    if (!isCurrent(token)) return
    chat.value.push({ role: 'event', content: error instanceof Error ? error.message : '请求失败' })
    running.value = false
    status.value = '请求失败'
  }
}

function listen(run: Run, token: number) {
  closeStream()
  const source = new EventSource(runtimeUrl(run.streamUrl))
  stream = source
  let answerIndex = -1
  let settled = false
  let reading = false
  let terminalObserved = false
  let polls = 0
  const seen = new Set<string>()
  const current = () => isCurrent(token) && !settled

  function on(type: string, handler: (payload: Record<string, unknown>) => void) {
    source.addEventListener(type, event => {
      if (!current()) return
      const messageEvent = event as MessageEvent
      if (messageEvent.lastEventId) {
        if (seen.has(messageEvent.lastEventId)) return
        seen.add(messageEvent.lastEventId)
      }
      handler(parsePayload(messageEvent.data))
    })
  }

  function clarification(payload: Record<string, unknown>) {
    if (payload.action !== 'CLARIFY' || !Array.isArray(payload.gapIds)) return
    const gapIds = payload.gapIds.filter((id): id is string => typeof id === 'string' && id.length > 0)
    if (gapIds.length) resumeState.value = { runId: run.id, gapIds }
  }

  on('message.delta', payload => {
    if (payload.content) {
      if (answerIndex < 0) {
        chat.value.push({ role: 'assistant', content: '' })
        answerIndex = chat.value.length - 1
      }
      chat.value[answerIndex].content += String(payload.content)
    }
  })
  on('tool.call.started', payload => {
    chat.value.push({ role: 'event', content: `调用工具：${payload.tool}` })
  })
  on('continuation.decided', clarification)

  function scheduleRead() {
    if (!current() || syncTimer || polls >= 3) return
    syncTimer = setTimeout(() => {
      syncTimer = undefined
      polls++
      void reconcile()
    }, 1_000)
  }

  async function reconcile() {
    if (!current() || reading) return
    reading = true
    syncingRun.value = true
    try {
      const latest = await getRun(run.id)
      if (!current()) return
      if (latest.id !== run.id || latest.conversationId !== run.conversationId) throw new Error('Run 状态不匹配')
      activeRun.value = latest
      if (!terminalStates.has(latest.state)) {
        status.value = terminalObserved ? '终态尚未保存，正在同步…' : '事件流重连中，Run 仍在执行…'
        syncIssue.value = terminalObserved || polls >= 3
        scheduleRead()
        return
      }
      terminalObserved = true
      // A lost continuation event must not turn clarification into a brand-new task.
      if (latest.state === 'NEEDS_INPUT' && !resumeState.value) {
        const events = await getRunEvents(run.id)
        if (!current()) return
        const last = [...events].reverse().find(event => event.type === 'continuation.decided')
        if (last) clarification(parsePayload(last.payload))
        if (!resumeState.value) throw new Error('缺少恢复所需的 Gap 信息')
      }
      settled = true
      closeStream()
      if (latest.outputMessage) {
        if (latest.state === 'COMPLETED' && answerIndex >= 0) chat.value[answerIndex].content = latest.outputMessage
        else chat.value.push({ role: latest.state === 'COMPLETED' ? 'assistant' : 'event', content: latest.outputMessage })
      }
      status.value = `${latest.state} · ${latest.turns} turns · ${latest.toolCalls} tools`
      if (latest.state === 'NEEDS_INPUT') {
        chat.value.push({ role: 'event', content: '需要补充信息；下一条消息会恢复当前计划。' })
      }
      running.value = false
      syncIssue.value = false
      retryRead = undefined
    } catch (error) {
      if (!current()) return
      status.value = `状态同步失败：${error instanceof Error ? error.message : '请重试同步'}`
      syncIssue.value = true
      // A known terminal event permits starting a separate conversation, never resending this message.
      if (terminalObserved) running.value = false
      scheduleRead()
    } finally {
      reading = false
      if (isCurrent(token)) syncingRun.value = false
    }
  }

  retryRead = async () => {
    if (syncTimer) clearTimeout(syncTimer)
    syncTimer = undefined
    polls = 0
    await reconcile()
  }
  const finish = () => {
    terminalObserved = true
    source.close()
    void reconcile()
  }
  for (const type of ['run.completed', 'run.failed', 'run.cancelled', 'run.needs_input']) on(type, finish)
  source.onerror = () => {
    if (!current()) return
    status.value = '事件流重连中…'
    scheduleRead()
  }
}

async function retrySync() { await retryRead?.() }

async function cancel() {
  if (!activeRun.value || !running.value) return
  const token = generation
  try {
    await cancelRun(activeRun.value.id)
    if (isCurrent(token) && running.value) status.value = '已请求取消…'
  } catch (error) {
    if (isCurrent(token) && running.value) status.value = `取消失败：${error instanceof Error ? error.message : '请重试'}`
  }
}

function newConversation() {
  if (running.value) return
  generation++
  closeStream()
  retryRead = undefined
  syncIssue.value = false
  syncingRun.value = false
  conversationId.value = undefined
  pinnedVersionId.value = undefined
  activeRun.value = undefined
  resumeState.value = undefined
  chat.value = [{ role: 'assistant', content: welcome }]
  status.value = '准备就绪'
}

function parsePayload(raw: string): Record<string, unknown> {
  try {
    const parsed = JSON.parse(raw)
    const payload = typeof parsed === 'string' ? JSON.parse(parsed) : parsed
    return payload && typeof payload === 'object' && !Array.isArray(payload) ? payload : {}
  } catch { return {} }
}

</script>

<template>
  <section class="chat-page">
    <div class="chat-toolbar">
      <div><span class="eyebrow">CONVERSATION</span><h1>对话</h1><p>{{ status }}</p></div>
      <div class="toolbar-actions">
        <el-select v-model="selectedAgentId" :disabled="running || !agents.length" placeholder="选择已发布 Agent" @change="newConversation">
          <el-option v-for="agent in agents" :key="agent.id" :label="`${agent.name} · v${agent.publishedVersionNo}`" :value="agent.id" />
        </el-select>
        <el-button :disabled="running" @click="newConversation">新会话</el-button>
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
          <span>{{ selectedAgent?.name ?? '未选择 Agent' }}<small v-if="pinnedVersionId"> · 固定版本 {{ pinnedVersionId.slice(0, 8) }}</small></span>
          <el-button v-if="syncIssue" :loading="syncingRun" @click="retrySync">重试同步</el-button>
          <el-button v-if="running" type="danger" @click="cancel">取消</el-button>
          <el-button v-else type="primary" class="primary-gradient" native-type="submit" :disabled="syncIssue || !message.trim() || !selectedAgentId">运行</el-button>
        </div>
      </form>
    </div>
  </section>
</template>
