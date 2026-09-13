import { ElMessage } from 'element-plus'

const duration = 2600

export const notifySuccess = (message: string) => ElMessage.success({ message, duration, showClose: true })
export const notifyError = (message: string) => ElMessage.error({ message, duration: 3600, showClose: true })
export const notifyWarning = (message: string) => ElMessage.warning({ message, duration, showClose: true })
