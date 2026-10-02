<template>
  <view class="help-page">
    <view class="help-hero">
      <view class="hero-badge">知识库</view>
      <text class="hero-title">帮助中心</text>
      <text class="hero-sub">
        以下 {{ documents.length }} 篇政策文档均已收录进智能客服知识库；客服回答会标注引用来源，点击来源即可回到本页查看原文。
      </text>
      <view v-if="knowledgeMeta" class="hero-meta">
        <text>知识库规模：{{ knowledgeMeta.documents }} 篇文档 / {{ knowledgeMeta.chunks }} 个索引分块</text>
        <text v-if="status?.retrieval">检索链路：{{ status.retrieval }}</text>
      </view>
      <button class="hero-button" @click="goAgent">向智能客服提问</button>
    </view>

    <view class="doc-list">
      <view v-for="doc in documents" :key="doc.source" class="doc-card">
        <view class="doc-head" @click="toggle(doc)">
          <view class="doc-title-box">
            <text class="doc-title">{{ doc.title }}</text>
            <text class="doc-meta">{{ typeLabel(doc.type) }} · 来源 {{ doc.source }}</text>
          </view>
          <text class="doc-toggle">{{ expanded[doc.source] ? '收起' : '查看全文' }}</text>
        </view>
        <view v-if="expanded[doc.source]" class="doc-body">
          <text class="doc-content">{{ contents[doc.source] || '加载中…' }}</text>
        </view>
      </view>
      <view v-if="!documents.length" class="doc-empty">
        知识库暂未收录文档。运行 scripts/start-demo.ps1 会自动灌入演示知识文档。
      </view>
    </view>

    <view class="help-cta" @click="goAgent">
      <text>没找到想问的？直接问智能客服，回答会附上引用来源 →</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import {
  agentStatusAPI,
  knowledgeDocumentsAPI,
  knowledgeSourceAPI,
  type AgentStatus,
  type KnowledgeDocument,
} from '@/apis/agent'

const documents = ref<KnowledgeDocument[]>([])
/** 来源标识 -> 原文全文 */
const contents = ref<Record<string, string>>({})
/** 来源标识 -> 是否展开 */
const expanded = ref<Record<string, boolean>>({})
const status = ref<AgentStatus | null>(null)
const pendingSource = ref('')

const knowledgeMeta = computed(() => status.value?.knowledgeBase)

const loadDocuments = async () => {
  try {
    const response = await knowledgeDocumentsAPI()
    documents.value = response.data?.documents || []
    // 从首页主题入口带参进入时，自动展开目标文档
    if (pendingSource.value) {
      const target = documents.value.find((doc) => doc.source === pendingSource.value)
      if (target) {
        await toggle(target, true)
      }
    }
  } catch {
    documents.value = []
  }
}

const loadStatus = async () => {
  try {
    const response = await agentStatusAPI()
    status.value = response.data
  } catch {
    status.value = null
  }
}

const toggle = async (doc: KnowledgeDocument, forceOpen = false) => {
  if (expanded.value[doc.source] && !forceOpen) {
    delete expanded.value[doc.source]
    return
  }
  if (!contents.value[doc.source]) {
    try {
      const response = await knowledgeSourceAPI(doc.source)
      contents.value[doc.source] = response.data?.found ? response.data.content : '（未找到原文）'
    } catch {
      contents.value[doc.source] = '原文读取失败，请稍后重试'
    }
  }
  expanded.value[doc.source] = true
}

const typeLabel = (type: string) => {
  if (type === 'policy') return '政策'
  if (type === 'faq') return '常见问题'
  if (type === 'manual') return '操作指引'
  return '文档'
}

const goAgent = () => {
  uni.navigateTo({ url: '/pages/agent/customer' })
}

onLoad((options?: Record<string, string>) => {
  if (options?.source) {
    pendingSource.value = decodeURIComponent(options.source)
  }
  loadDocuments()
  loadStatus()
})
</script>

<style lang="scss" scoped>
.help-page {
  min-height: 100vh;
  padding: 24rpx 30rpx 60rpx;
  background: #f4f6fb;
  box-sizing: border-box;
}

.help-hero {
  padding: 34rpx 30rpx;
  border-radius: 22rpx;
  color: #fff;
  background: linear-gradient(115deg, #253878, #4870d7 62%, #36a99b);
  box-shadow: 0 12rpx 26rpx rgba(53, 86, 166, 0.2);
}

.hero-badge {
  display: inline-block;
  padding: 4rpx 16rpx;
  border-radius: 999rpx;
  background: rgba(255, 255, 255, 0.18);
  font-size: 20rpx;
  letter-spacing: 2rpx;
}

.hero-title {
  display: block;
  margin-top: 14rpx;
  font-size: 40rpx;
  font-weight: 700;
}

.hero-sub {
  display: block;
  margin-top: 12rpx;
  color: rgba(255, 255, 255, 0.86);
  font-size: 24rpx;
  line-height: 1.7;
}

.hero-meta {
  margin-top: 16rpx;
  padding-top: 14rpx;
  border-top: 1rpx solid rgba(255, 255, 255, 0.18);
}

.hero-meta text {
  display: block;
  color: rgba(255, 255, 255, 0.74);
  font-size: 22rpx;
  line-height: 1.7;
}

.hero-button {
  margin: 24rpx 0 0;
  height: 76rpx;
  border-radius: 14rpx;
  color: #3556a6;
  background: #fff;
  font-size: 28rpx;
  font-weight: 700;
  line-height: 76rpx;
}

.doc-list {
  margin-top: 24rpx;
}

.doc-card {
  margin-bottom: 20rpx;
  border-radius: 18rpx;
  background: #fff;
  box-shadow: 0 6rpx 18rpx rgba(31, 48, 94, 0.06);
  overflow: hidden;
}

.doc-head {
  display: flex;
  align-items: center;
  padding: 26rpx 26rpx;
}

.doc-title-box {
  flex: 1;
}

.doc-title {
  display: block;
  color: #222;
  font-size: 30rpx;
  font-weight: 700;
}

.doc-meta {
  display: block;
  margin-top: 8rpx;
  color: #8a90a3;
  font-size: 22rpx;
}

.doc-toggle {
  margin-left: 16rpx;
  color: #4870d7;
  font-size: 24rpx;
}

.doc-body {
  padding: 0 26rpx 28rpx;
}

.doc-content {
  display: block;
  padding: 22rpx;
  border-radius: 14rpx;
  background: #f7f9fd;
  color: #3c4257;
  font-size: 26rpx;
  line-height: 1.9;
  white-space: pre-wrap;
}

.doc-empty {
  padding: 60rpx 30rpx;
  border-radius: 18rpx;
  background: #fff;
  color: #8a90a3;
  font-size: 26rpx;
  text-align: center;
}

.help-cta {
  margin-top: 10rpx;
  padding: 26rpx;
  border-radius: 18rpx;
  color: #3556a6;
  background: rgba(72, 112, 215, 0.1);
  font-size: 26rpx;
  text-align: center;
}
</style>