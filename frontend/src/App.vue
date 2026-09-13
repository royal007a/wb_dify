<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ChatDotRound, Fold, Setting, User, UserFilled } from '@element-plus/icons-vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const manuallyCollapsed = ref(false)
const compactViewport = ref(false)
const collapsed = computed(() => compactViewport.value || manuallyCollapsed.value)
const sidebarWidth = computed(() => collapsed.value ? 'var(--sidebar-collapsed-width)' : 'var(--sidebar-width)')
const pageTitle = computed(() => String(route.meta.title ?? '工作台'))
let media: MediaQueryList | undefined

function syncViewport(event: MediaQueryList | MediaQueryListEvent) {
  compactViewport.value = event.matches
}

onMounted(() => {
  media = window.matchMedia('(max-width: 1199px)')
  syncViewport(media)
  media.addEventListener('change', syncViewport)
})

onBeforeUnmount(() => media?.removeEventListener('change', syncViewport))
</script>

<template>
  <el-container class="app-shell" :class="{ 'is-collapsed': collapsed }">
    <el-aside :width="sidebarWidth" class="app-sidebar">
      <div class="brand" aria-label="Hify AI Agent Platform">
        <span class="brand-mark">H</span>
        <div class="brand-copy">
          <strong>Hify</strong>
          <span>AI Agent Platform</span>
        </div>
      </div>

      <el-menu :default-active="route.path" router :collapse="collapsed" class="app-menu">
        <el-menu-item index="/providers">
          <el-icon><Setting /></el-icon>
          <template #title>模型管理</template>
        </el-menu-item>
        <el-menu-item index="/agents">
          <el-icon><User /></el-icon>
          <template #title>Agent 管理</template>
        </el-menu-item>
        <el-menu-item index="/chat">
          <el-icon><ChatDotRound /></el-icon>
          <template #title>对话</template>
        </el-menu-item>
      </el-menu>

      <div class="sidebar-foot">
        <button
          class="collapse-button"
          type="button"
          :aria-label="collapsed ? '展开侧边栏' : '折叠侧边栏'"
          :disabled="compactViewport"
          @click="manuallyCollapsed = !manuallyCollapsed"
        >
          <el-icon><Fold /></el-icon>
          <span>折叠导航</span>
        </button>
        <span class="version">v0.1.0</span>
      </div>
    </el-aside>

    <el-container class="app-content">
      <el-header class="app-topbar">
        <el-breadcrumb separator="/">
          <el-breadcrumb-item>工作台</el-breadcrumb-item>
          <el-breadcrumb-item>{{ pageTitle }}</el-breadcrumb-item>
        </el-breadcrumb>
        <div class="user-chip">
          <el-avatar :size="30"><el-icon><UserFilled /></el-icon></el-avatar>
          <span>Hify Admin</span>
        </div>
      </el-header>
      <el-main class="app-main"><router-view /></el-main>
    </el-container>
  </el-container>
</template>
