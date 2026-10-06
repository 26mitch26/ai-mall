<template>
  <view class="content pc-storefront-page">
    <!-- #ifdef H5 -->
    <pc-storefront-nav active="category" />
    <!-- #endif -->
    <scroll-view scroll-y class="left-aside">
      <view
        v-for="item in topCateList"
        :key="item.id"
        class="f-item b-b"
        :class="{ active: item.id === currentCateId }"
        @click="handleTabTap(item)"
      >
        {{ item.name }}
      </view>
    </scroll-view>
    <scroll-view scroll-with-animation scroll-y class="right-aside">
      <view class="s-list">
        <view
          @click="handleNavToList(item.id)"
          class="s-item"
          v-for="item in subCateList"
          :key="item.id"
        >
          <image
            :src="
              item.icon ||
              'http://macro-oss.oss-cn-shenzhen.aliyuncs.com/mall/images/20190519/default.png'
            "
          ></image>
          <text>{{ item.name }}</text>
        </view>
      </view>
    </scroll-view>
  </view>
</template>

<script setup lang="ts">
import PcStorefrontNav from '@/components/pc-storefront-nav.vue'
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { getProductCateListAPI } from '@/apis/home'
import type { PmsProductCategory } from '@/types/product'

// ===== 页面数据 =====
// 当前分类id
const currentCateId = ref(0)
// 一级分类列表
const topCateList = ref<PmsProductCategory[]>([])
// 二级分类列表
const subCateList = ref<PmsProductCategory[]>([])

// ===== 数据加载 =====
// 加载分类数据
const loadData = async () => {
  try {
    // 获取一级分类数据
    const res = await getProductCateListAPI('0')
    topCateList.value = res.data
    if (topCateList.value.length > 0) {
      currentCateId.value = topCateList.value[0].id
      // 获取二级分类数据
      const subRes = await getProductCateListAPI(String(currentCateId.value))
      subCateList.value = subRes.data
    }
  } catch (e) {
    console.error('加载分类数据失败', e)
  }
}

// ===== 生命周期 =====
// 页面加载时执行
onLoad(() => {
  loadData()
})

// ===== 事件处理方法 =====
// 一级分类点击
const handleTabTap = async (item: PmsProductCategory) => {
  currentCateId.value = item.id
  try {
    const res = await getProductCateListAPI(String(item.id))
    subCateList.value = res.data
  } catch (e) {
    console.error('加载子分类失败', e)
  }
}

// 导航到商品列表
const handleNavToList = (sid: number) => {
  uni.navigateTo({
    url: `/pages/product/list?fid=${currentCateId.value}&sid=${sid}`,
  })
}
</script>

<style lang="scss" scoped>
@media screen and (min-width: 769px) {
  .content {
    display: flex;
    align-items: stretch;
    gap: 24px;
    width: min(1240px, calc(100% - 64px));
    height: auto;
    min-height: calc(100vh - 80px);
    margin: 0 auto;
    padding: 24px 0 40px;
    box-sizing: border-box;
  }

  .left-aside {
    flex: 0 0 220px;
    height: auto;
    border: 1px solid #edf0f6;
    border-radius: 16px;
    background: #fff;
    box-shadow: 0 6px 20px rgba(31, 48, 94, 0.04);
  }

  .right-aside {
    flex: 1;
    height: auto;
    padding: 22px;
    border: 1px solid #edf0f6;
    border-radius: 16px;
    background: #fff;
  }

  .s-list {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 18px;
    width: 100%;
  }

  .s-item {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 8px;
    width: auto;
    padding: 14px 8px;
    border-radius: 12px;
    background: #f8f9fc;
  }

  .s-item image {
    width: 78px;
    height: 78px;
  }
}
</style>

<style lang="scss">
page {
  height: 100%;
  background-color: #f8f8f8;
}
</style>

<style lang="scss" scoped>
.content {
  height: 100%;
  background-color: #f8f8f8;
  display: flex;
}

.left-aside {
  flex-shrink: 0;
  width: 25%;
  max-width: 120px;
  height: 100%;
  background-color: #fff;
}

.f-item {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100rpx;
  font-size: 28rpx;
  color: #606266;
  position: relative;

  &.active {
    color: #fa436a;
    background: #f8f8f8;

    &::before {
      content: '';
      position: absolute;
      left: 0;
      top: 50%;
      transform: translateY(-50%);
      height: 36rpx;
      width: 8rpx;
      background-color: #fa436a;
      border-radius: 0 4px 4px 0;
      opacity: 0.8;
    }
  }
}

.right-aside {
  flex: 1;
  overflow: hidden;
  padding-left: 20rpx;
}

.s-list {
  margin-top: 20rpx;
  display: flex;
  flex-wrap: wrap;
  width: 100%;
  background: #fff;
  padding-top: 12rpx;

  &::after {
    content: '';
    flex: 99;
    height: 0;
  }
}

.s-item {
  flex-shrink: 0;
  display: flex;
  justify-content: center;
  align-items: center;
  flex-direction: column;
  width: 33%;
  font-size: 26rpx;
  color: #666;
  padding-bottom: 20rpx;

  image {
    width: 140rpx;
    height: 140rpx;
  }
}
</style>
