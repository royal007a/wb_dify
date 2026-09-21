import { createRouter, createWebHistory } from 'vue-router'
import ProviderList from '@/views/provider/ProviderList.vue'
import AgentList from '@/views/AgentList.vue'
import ChatView from '@/views/ChatView.vue'
import KnowledgeList from '@/views/KnowledgeList.vue'
import WorkflowList from '@/views/WorkflowList.vue'
import McpServerList from '@/views/McpServerList.vue'

export const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', redirect: '/providers' },
    { path: '/providers', name: 'providers', component: ProviderList, meta: { title: '模型管理' } },
    { path: '/agents', name: 'agents', component: AgentList, meta: { title: 'Agent 管理' } },
    { path: '/knowledge', name: 'knowledge', component: KnowledgeList, meta: { title: '知识库' } },
    { path: '/workflows', name: 'workflows', component: WorkflowList, meta: { title: 'Workflow' } },
    { path: '/mcp', name: 'mcp', component: McpServerList, meta: { title: 'MCP Servers' } },
    { path: '/chat', name: 'chat', component: ChatView, meta: { title: '对话' } },
  ],
})
