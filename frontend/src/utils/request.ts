import axios, { type AxiosRequestConfig } from 'axios'
import { notifyError } from '@/utils/notify'

export interface ApiResult<T> {
  code: number
  message: string
  data: T
}

interface ApiPageResult<T> extends ApiResult<T[]> {
  total: number
  page: number
  size: number
}

interface HifyHttpClient {
  get<T>(url: string, config?: AxiosRequestConfig): Promise<T>
  post<T>(url: string, data?: object, config?: AxiosRequestConfig): Promise<T>
  put<T>(url: string, data?: object, config?: AxiosRequestConfig): Promise<T>
  delete<T>(url: string, config?: AxiosRequestConfig): Promise<T>
}

const axiosInstance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 60_000,
})

axiosInstance.interceptors.response.use(
  response => {
    const payload = response.data as ApiResult<unknown>
    if (payload.code !== 200) {
      const message = payload.message || '请求失败'
      notifyError(message)
      return Promise.reject(new Error(message))
    }
    if (Array.isArray(payload.data) && typeof (payload as ApiPageResult<unknown>).total === 'number') {
      const page = payload as ApiPageResult<unknown>
      return { data: page.data, total: page.total, page: page.page, size: page.size } as never
    }
    // Runtime contract: successful calls resolve to Result.data, not AxiosResponse.
    return payload.data as never
  },
  error => {
    const message = error.response?.data?.message || error.message || '网络异常'
    notifyError(message)
    return Promise.reject(error)
  },
)

const instance = axiosInstance as unknown as HifyHttpClient

export const get = <T>(url: string, params?: object, config?: AxiosRequestConfig): Promise<T> =>
  instance.get<T>(url, { ...config, params })

export const post = <T>(url: string, data?: object, config?: AxiosRequestConfig): Promise<T> =>
  instance.post<T>(url, data, config)

export const put = <T>(url: string, data?: object, config?: AxiosRequestConfig): Promise<T> =>
  instance.put<T>(url, data, config)

export const del = <T>(url: string, config?: AxiosRequestConfig): Promise<T> =>
  instance.delete<T>(url, config)
