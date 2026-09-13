import { readonly, ref } from 'vue'

export function useRequest<T, TArgs extends unknown[]>(api: (...args: TArgs) => Promise<T>) {
  const data = ref<T>()
  const loading = ref(false)
  const error = ref<Error>()

  async function execute(...args: TArgs): Promise<T> {
    loading.value = true
    error.value = undefined
    try {
      const result = await api(...args)
      data.value = result
      return result
    } catch (caught) {
      error.value = caught instanceof Error ? caught : new Error('请求失败')
      throw error.value
    } finally {
      loading.value = false
    }
  }

  return { data: readonly(data), loading: readonly(loading), error: readonly(error), execute }
}
