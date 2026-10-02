import { createSSRApp } from 'vue'
import { createPinia } from 'pinia'
import persist from 'pinia-plugin-persistedstate'

import App from './App.vue'
// components 目录为平铺文件，不符合 easycom 的"目录/同名文件"约定，组件无法自动解析；
// 这里统一全局注册（实测订单页出现 "Failed to resolve component: empty" 警告）。
import Empty from '@/components/empty.vue'
import MixListCell from '@/components/mix-list-cell.vue'
import UniNumberBox from '@/components/uni-number-box.vue'

export function createApp() {
  // 创建 Vue 应用
  const app = createSSRApp(App)
  // 创建 Pinia
  const pinia = createPinia()
  // 使用 Pinia 持久化插件
  pinia.use(persist)
  // 使用 Pinia 插件
  app.use(pinia)
  // 全局注册平铺组件
  app.component('empty', Empty)
  app.component('mix-list-cell', MixListCell)
  app.component('uni-number-box', UniNumberBox)

  return {
    app,
  }
}
