import { useUserStore } from '@/stores/user'
import type { CommonResult } from '@/types/common'
import axios, { AxiosHeaders } from 'axios'
import { ElMessage, ElMessageBox } from 'element-plus'

// 创建axios实例
const http = axios.create({
  baseURL: import.meta.env.VITE_BASE_SERVER_URL,
  timeout: 5000,
})

const hasExplicitAuthorization = (headers?: AxiosHeaders) => AxiosHeaders.from(headers).has('Authorization')

// axios请求拦截器
http.interceptors.request.use(
  config => {
    // Customer APIs pass an explicit member token, or an explicit empty value for anonymous use.
    // Preserve that choice instead of silently replacing it with the admin session token.
    if (!hasExplicitAuthorization(config.headers)) {
      const userStore = useUserStore()
      const token = userStore.userInfo.token
      if (token) config.headers.set('Authorization', token)
    }
    return config
  },
  e => Promise.reject(e),
)

// axios响应拦截器
http.interceptors.response.use(
  response => {
    const res: CommonResult<unknown> = response.data
    // 电商服务统一返回 CommonResult；三个 Agent 服务返回原生领域 JSON。
    // 在前端边界统一归一化，页面仍可稳定使用 res.data。
    if (typeof res?.code !== 'number') {
      return {
        code: 200,
        message: 'success',
        data: response.data,
      }
    }
    if (res.code !== 200) {
      // code为非200是抛错，这里统一处理提示信息
      ElMessage({
        message: res.message,
        type: 'error',
        duration: 3 * 1000,
      })
      // 401:未登录;
      if (res.code === 401 && !hasExplicitAuthorization(response.config.headers)) {
        ElMessageBox.confirm('你已被登出，可以取消继续留在该页面，或者重新登录', '确定登出', {
          confirmButtonText: '重新登录',
          cancelButtonText: '取消',
          type: 'warning',
        }).then(() => {
          const userStore = useUserStore()
          userStore.fedLogout()
          // 为了重新实例化vue-router对象 避免bug
          location.reload()
        })
      }
      return Promise.reject('error')
    } else {
      // 返回响应JSON中的data属性，不包括message和code
      return response.data
    }
  },
  error => {
    // 全局处理异常请求
    ElMessage({
      message: error.message,
      type: 'error',
      duration: 3 * 1000,
    })
    return Promise.reject(error)
  },
)

export default http
