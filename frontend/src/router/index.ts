import { createRouter, createWebHistory } from 'vue-router'
import ProviderList from '@/views/provider/ProviderList.vue'
import AgentList from '@/views/AgentList.vue'
import ChatView from '@/views/ChatView.vue'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/providers' },
    { path: '/providers', name: 'providers', component: ProviderList, meta: { title: '模型管理' } },
    { path: '/agents', name: 'agents', component: AgentList, meta: { title: 'Agent 管理' } },
    { path: '/chat', name: 'chat', component: ChatView, meta: { title: '对话' } },
  ],
})
