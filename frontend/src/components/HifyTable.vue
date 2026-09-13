<script setup lang="ts" generic="T extends Record<string, unknown>">
import { onMounted, reactive, ref } from 'vue'

export interface HifyColumn<T> {
  label: string
  prop?: keyof T & string
  width?: string | number
  minWidth?: string | number
  slot?: string
  className?: string
  align?: 'left' | 'center' | 'right'
}

export interface HifyPageResult<T> {
  data: T[]
  total: number
  page: number
  size: number
}

const props = withDefaults(defineProps<{
  columns: HifyColumn<T>[]
  api: (params: { page: number; pageSize: number }) => Promise<HifyPageResult<T>>
  showPagination?: boolean
  rowKey?: keyof T & string
}>(), {
  showPagination: true,
  rowKey: 'id',
})

const rows = ref<T[]>([])
const loading = ref(false)
const pagination = reactive({ page: 1, pageSize: 10, total: 0 })

async function refresh(resetPage = false) {
  if (resetPage) pagination.page = 1
  loading.value = true
  try {
    const result = await props.api({ page: pagination.page, pageSize: pagination.pageSize })
    rows.value = result.data
    pagination.total = result.total
    pagination.page = result.page
    pagination.pageSize = result.size
  } finally {
    loading.value = false
  }
}

function changePage(page: number) {
  pagination.page = page
  void refresh()
}

function changePageSize(size: number) {
  pagination.pageSize = size
  pagination.page = 1
  void refresh()
}

onMounted(() => { void refresh().catch(() => undefined) })
defineExpose({ refresh })
</script>

<template>
  <div class="hify-table">
    <el-table v-loading="loading" :data="rows" :row-key="rowKey" :row-style="{ height: '52px' }" empty-text="" stripe>
      <el-table-column
        v-for="column in columns"
        :key="column.slot ?? column.prop ?? column.label"
        :prop="column.prop"
        :label="column.label"
        :width="column.width"
        :min-width="column.minWidth"
        :class-name="column.className"
        :label-class-name="column.className"
        :align="column.align ?? 'left'"
      >
        <template v-if="column.slot" #default="scope">
          <slot :name="column.slot" :row="scope.row as T" :index="scope.$index" />
        </template>
      </el-table-column>
      <template #empty><el-empty description="暂无数据" :image-size="72" /></template>
    </el-table>
    <div v-if="showPagination" class="hify-pagination">
      <span>共 {{ pagination.total }} 条</span>
      <el-pagination
        background
        layout="sizes, prev, pager, next"
        :current-page="pagination.page"
        :page-size="pagination.pageSize"
        :page-sizes="[10, 20, 50, 100]"
        :total="pagination.total"
        @update:current-page="changePage"
        @update:page-size="changePageSize"
      />
    </div>
  </div>
</template>

<style scoped>
.hify-table { overflow: hidden; border: 1px solid var(--color-border); border-radius: var(--radius-md); }
.hify-table :deep(.el-table__header th) { height: 44px; color: var(--color-text-secondary); font-size: 12px; font-weight: 700; background: var(--color-bg-secondary) !important; }
.hify-table :deep(.el-table__row) { transition: background var(--duration-fast); }
.hify-table :deep(.el-table__row:hover > td.el-table__cell) { background: var(--color-primary-50) !important; }
.hify-table :deep(.el-table__inner-wrapper::before) { display: none; }
.hify-pagination { display: flex; align-items: center; justify-content: flex-end; gap: 18px; min-height: 58px; padding: 10px 14px; color: var(--color-text-tertiary); font-size: 12px; border-top: 1px solid var(--color-divider); }
@media (max-width: 760px) { .hify-pagination { align-items: flex-end; flex-direction: column; } }
</style>
