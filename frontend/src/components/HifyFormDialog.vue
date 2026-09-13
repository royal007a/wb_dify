<script setup lang="ts" generic="T extends Record<string, unknown>">
import { nextTick, ref } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'

const props = withDefaults(defineProps<{
  modelValue: boolean
  title: string
  width?: string | number
  rules?: FormRules<T>
  initialValue: () => T
}>(), { width: 520 })

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  submit: [value: T, done: (success?: boolean) => void]
}>()

const formRef = ref<FormInstance>()
const formModel = ref<T>(props.initialValue())
const submitting = ref(false)

async function open(data?: Partial<T>) {
  formModel.value = { ...props.initialValue(), ...(data ?? {}) }
  emit('update:modelValue', true)
  await nextTick()
  formRef.value?.clearValidate()
}

function close() {
  if (!submitting.value) emit('update:modelValue', false)
}

function reset() {
  submitting.value = false
  formModel.value = props.initialValue()
  formRef.value?.resetFields()
}

async function submit() {
  if (!formRef.value || submitting.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  emit('submit', { ...formModel.value }, (success = true) => {
    submitting.value = false
    if (success) emit('update:modelValue', false)
  })
}

defineExpose({ open, close, validate: () => formRef.value?.validate() })
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    :width="width"
    destroy-on-close
    align-center
    @update:model-value="emit('update:modelValue', $event)"
    @closed="reset"
  >
    <el-form ref="formRef" :model="formModel" :rules="rules" label-width="100px" label-position="right">
      <slot :model="formModel" />
    </el-form>
    <template #footer>
      <el-button :disabled="submitting" @click="close">取消</el-button>
      <el-button type="primary" class="primary-gradient" :loading="submitting" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
:global(.el-dialog) { max-width: calc(100vw - 32px); border-radius: var(--radius-lg); box-shadow: var(--shadow-md); }
:global(.el-dialog__header) { padding-bottom: 16px; border-bottom: 1px solid var(--color-divider); }
:global(.el-dialog__footer) { padding-top: 16px; border-top: 1px solid var(--color-divider); }
</style>
