import { ref, toValue, type MaybeRefOrGetter } from 'vue'
import { ElMessageBox } from 'element-plus'
import { notifySuccess } from '@/utils/notify'

export function useConfirm<TArgs extends unknown[]>(
  message: MaybeRefOrGetter<string>,
  action: (...args: TArgs) => Promise<unknown>,
  successMessage = '操作成功',
) {
  const confirming = ref(false)

  async function execute(...args: TArgs): Promise<boolean> {
    try {
      await ElMessageBox.confirm(toValue(message), '请确认', {
        type: 'warning',
        confirmButtonText: '确认',
        cancelButtonText: '取消',
        autofocus: false,
      })
      confirming.value = true
      await action(...args)
      notifySuccess(successMessage)
      return true
    } catch (error) {
      if (error === 'cancel' || error === 'close') return false
      throw error
    } finally {
      confirming.value = false
    }
  }

  return { confirming, execute }
}
