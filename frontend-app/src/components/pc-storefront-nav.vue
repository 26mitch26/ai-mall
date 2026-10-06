<template>
  <!-- #ifdef H5 -->
  <view class="pc-storefront-nav">
    <view class="pc-storefront-nav__inner">
      <view class="pc-storefront-brand" @click="switchTab('/pages/index/index')">
        <text class="pc-storefront-brand__mark">AI</text>
        <text>AI-Mall</text>
      </view>
      <view class="pc-storefront-links">
        <view
          v-for="item in links"
          :key="item.key"
          class="pc-storefront-link"
          :class="{ 'is-active': active === item.key, 'is-emphasis': item.key === 'customer' }"
          @click="navigate(item)"
        >
          {{ item.label }}
        </view>
      </view>
    </view>
  </view>
  <!-- #endif -->
</template>

<script setup lang="ts">
type NavItem = {
  key: string
  label: string
  url: string
  tab?: boolean
}

const props = defineProps<{ active?: string }>()

const links: NavItem[] = [
  { key: 'home', label: '首页', url: '/pages/index/index', tab: true },
  { key: 'category', label: '商品分类', url: '/pages/category/category', tab: true },
  { key: 'customer', label: '智能客服', url: '/pages/agent/customer' },
  { key: 'help', label: '帮助中心', url: '/pages/help/help' },
  { key: 'cart', label: '购物车', url: '/pages/cart/cart', tab: true },
  { key: 'user', label: '我的商城', url: '/pages/user/user', tab: true },
]

const switchTab = (url: string) => uni.switchTab({ url })
const navigateTo = (url: string) => uni.navigateTo({ url })
const navigate = (item: NavItem) => {
  if (props.active === item.key) return
  if (item.tab) switchTab(item.url)
  else navigateTo(item.url)
}
</script>
