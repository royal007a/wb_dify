<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { Agent } from '@/api/agents'
import {
  cancelRun,
  createConversation,
  createRun,
  getRun,
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
const chat = ref<ChatItem[]>([{ role: 'assistant', content: '你好，我是 Hify Demo Agent。可以问我时间，或让我计算 12.5 * 4。' }])
const selectedAgent = computed(() => agents.value.find(agent => agent.id === selectedAgentId.value))

onMounted(loadAgents)

async function loadAgents() {
  try {
    agents.value = await listRunnableAgents()
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
  if (!text || running.value) return
  running.value = true
  status.value = '创建 Run…'
  chat.value.push({ role: 'user', content: text })
  message.value = ''
  try {
    const conversation = await ensureConversation()
    activeRun.value = await createRun(conversation, text, resumeState.value)
    resumeState.value = undefined
    status.value = `Run ${activeRun.value!.id.slice(0, 8)} 执行中`
    listen(activeRun.value!)
  } catch (error) {
    chat.value.push({ role: 'event', content: error instanceof Error ? error.message : '请求失败' })
    running.value = false
    status.value = '请求失败'
  }
}

function listen(run: Run) {
  const source = new EventSource(runtimeUrl(run.streamUrl))
  let answerAdded = false
  let answerIndex = -1
  let settled = false
  source.addEventListener('message.delta', event => {
    const payload = parsePayload((event as MessageEvent).data)
    if (payload.content) {
      if (answerIndex < 0) {
        chat.value.push({ role: 'assistant', content: '' })
        answerIndex = chat.value.length - 1
      }
      chat.value[answerIndex].content += String(payload.content)
      answerAdded = true
    }
  })
  source.addEventListener('tool.call.started', event => {
    const payload = parsePayload((event as MessageEvent).data)
    chat.value.push({ role: 'event', content: `调用工具：${payload.tool}` })
  })
  source.addEventListener('continuation.decided', event => {
    const payload = parsePayload((event as MessageEvent).data)
    if (payload.action === 'CLARIFY' && Array.isArray(payload.gapIds) && payload.gapIds.length > 0) {
      resumeState.value = { runId: run.id, gapIds: payload.gapIds.map(String) }
    }
  })
  const finish = async () => {
    if (settled) return
    settled = true
    source.close()
    const latest = await getRun(run.id)
    activeRun.value = latest
    if (!answerAdded && latest.outputMessage) chat.value.push({ role: latest.state === 'COMPLETED' ? 'assistant' : 'event', content: latest.outputMessage })
    status.value = `${latest.state} · ${latest.turns} turns · ${latest.toolCalls} tools`
    if (latest.state === 'NEEDS_INPUT') {
      chat.value.push({ role: 'event', content: '需要补充信息；下一条消息会恢复当前计划。' })
    }
    running.value = false
  }
  source.addEventListener('run.completed', finish)
  source.addEventListener('run.failed', finish)
  source.addEventListener('run.cancelled', finish)
  source.addEventListener('run.needs_input', finish)
  source.onerror = () => {
    if (!settled) status.value = '事件流重连中…'
  }
}

async function cancel() {
  if (!activeRun.value || !running.value) return
  await cancelRun(activeRun.value.id)
  status.value = '已请求取消…'
}

function newConversation() {
  conversationId.value = undefined
  pinnedVersionId.value = undefined
  activeRun.value = undefined
  resumeState.value = undefined
  chat.value = [{ role: 'assistant', content: '新会话已就绪。' }]
  status.value = '准备就绪'
}

function parsePayload(raw: string): Record<string, unknown> {
  try {
    const parsed = JSON.parse(raw)
    return typeof parsed === 'string' ? JSON.parse(parsed) : parsed
  } catch { return { content: raw } }
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
          <span>{{ selectedAgent?.name ?? '未选择 Agent' }}<small v-if="pinnedVersionId"> · 固定版本 {{ pinnedVersionId.slice(0, 8) }}</small></span>
          <el-button v-if="running" type="danger" @click="cancel">取消</el-button>
          <el-button v-else type="primary" class="primary-gradient" native-type="submit" :disabled="!message.trim() || !selectedAgentId">运行</el-button>
        </div>
      </form>
    </div>
  </section>
</template>
