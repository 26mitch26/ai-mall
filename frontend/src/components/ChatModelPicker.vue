<template>
  <section class="model-panel" aria-label="聊天模型设置">
    <div class="model-row">
      <select aria-label="聊天模型" :value="selectedChoice" :disabled="disabled || busy" @change="chooseModel">
        <option value="">本地默认{{ catalog?.defaultModel ? ` · ${catalog.defaultModel}` : '' }}</option>
        <option v-for="entry in chatModels" :key="entry.name" :value="entry.name">{{ entry.name }} · {{ (entry.size / 1024 ** 3).toFixed(1) }}GB</option>
        <option value="__custom__">自定义本地模型 / 云端 API</option>
      </select>
      <button type="button" :disabled="busy || disabled" @click="loadModels(true)">{{ busy ? '读取中' : '刷新' }}</button>
      <button type="button" :disabled="disabled" @click="editing = !editing">设置</button>
    </div>
    <div v-if="editing" class="model-fields">
      <label>接口类型<select v-model="draft.provider" aria-label="模型接口类型" :disabled="disabled" @change="changeProvider"><option value="ollama">本地 Ollama</option><option value="openai-compatible">云端 OpenAI 兼容 API</option></select></label>
      <label>模型名<input v-model="draft.model" aria-label="自定义模型名" :disabled="disabled" placeholder="填写已安装的 Ollama 模型名或云端模型名" /></label>
      <template v-if="draft.provider === 'openai-compatible'">
        <label>API 地址<input v-model="draft.baseUrl" aria-label="云端 API 地址" :disabled="disabled" placeholder="https://api.example.com/v1" /></label>
        <label>API Key<input v-model="draft.apiKey" aria-label="云端 API Key" :disabled="disabled" type="password" autocomplete="off" placeholder="仅保留在本页内存，不长期存储" /></label>
        <p class="model-hint">需要生成时，会把本轮上下文发送到你填写的云端地址。Key 不保存到浏览器存储或会话历史。测试连接会调用一次云端模型，可能计费；仅发送固定测试文本。</p>
      </template>
    </div>
    <div v-if="editing" class="model-row model-actions"><button type="button" :disabled="disabled" @click="applyDraft">应用设置</button><button type="button" :disabled="disabled || probing" @click="probeDraft">{{ probing ? '测试中…' : '测试连接（短回复）' }}</button></div>
    <p class="model-hint">选择作用于需要模型的步骤；政策原文和工具直答通常无需生成。读取列表不会加载模型，GB 为磁盘大小。</p>
    <p v-if="note" class="model-note" role="status">{{ note }}</p>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { getChatModelsAPI, testChatModelAPI, type ChatModelCatalog, type ChatModelConfig } from '@/apis/agent'
const props = defineProps<{ modelValue?: ChatModelConfig; disabled?: boolean; resetKey?: number }>()
const emit = defineEmits<{ (event: 'update:modelValue', value: ChatModelConfig | undefined): void }>()
const catalog = ref<ChatModelCatalog>()
const editing = ref(false)
const busy = ref(false)
const probing = ref(false)
const note = ref('')
const draft = reactive<ChatModelConfig>({ provider: 'ollama', model: '', baseUrl: '', apiKey: '' })
const chatModels = computed(() => catalog.value?.models.filter(m => m.canChat) || [])
const selectedChoice = computed(() => !props.modelValue ? '' : props.modelValue.provider === 'ollama' && chatModels.value.some(m => m.name === props.modelValue?.model) ? props.modelValue.model : '__custom__')
const loadModels = async (refresh = false) => {
  busy.value = true
  try { catalog.value = (await getChatModelsAPI(refresh)).data; if (!draft.model && draft.provider === 'ollama') draft.model = catalog.value.defaultModel; note.value = `发现 ${chatModels.value.length} 个本地聊天模型；向量模型不用于对话。` }
  catch { note.value = '无法读取 Ollama 模型，请检查本地服务后刷新。' }
  finally { busy.value = false }
}
const chooseModel = (event: Event) => {
  const name = (event.target as HTMLSelectElement).value
  if (name === '__custom__') { editing.value = true; return }
  emit('update:modelValue', name ? { provider: 'ollama', model: name } : undefined)
  draft.provider = 'ollama'; draft.model = name || catalog.value?.defaultModel || ''; draft.apiKey = ''; draft.baseUrl = ''
  note.value = '已选择本地模型；将在需要生成的下一条回复中使用。'
}
const changeProvider = () => { draft.model = ''; draft.apiKey = ''; note.value = '' }
const configuration = (): ChatModelConfig => {
  const model = draft.model.trim()
  if (!model) throw new Error('请填写模型名。')
  if (draft.provider === 'ollama') {
    if (!chatModels.value.some(m => m.name === model || m.name === `${model}:latest`)) throw new Error('该聊天模型不在已安装列表中，请先安装并刷新。')
    return { provider: 'ollama', model }
  }
  if (!draft.baseUrl?.trim().startsWith('https://') || !draft.apiKey?.trim()) throw new Error('请填写 HTTPS 地址和 API Key。')
  return { provider: draft.provider, model, baseUrl: draft.baseUrl.trim(), apiKey: draft.apiKey.trim() }
}
const applyDraft = () => {
  try { emit('update:modelValue', configuration()); editing.value = false; note.value = '设置已应用；只作用于当前页面的生成请求。' }
  catch (error) { note.value = error instanceof Error ? error.message : '请检查设置。' }
}
const probeDraft = async () => {
  try {
    const config = configuration(); probing.value = true
    const result = (await testChatModelAPI(config)).data
    note.value = `测试成功 · ${result.usedModels?.join('、') || result.selectedModel}：${result.reply}`
  } catch (error) {
    const failure = error as { data?: { message?: string }; response?: { data?: { message?: string } } }
    const message = failure.data?.message || failure.response?.data?.message || ''
    note.value = message.startsWith('当前可用内存不足') ? message : '测试失败：请检查服务、地址、模型和密钥；也可能因可用内存不足被暂停。'
  }
  finally { probing.value = false }
}
watch(() => props.resetKey, () => { draft.apiKey = ''; if (draft.provider === 'openai-compatible') note.value = '页面已离开，云端 Key 已清空。' })
onBeforeUnmount(() => { draft.apiKey = '' })
onMounted(() => { void loadModels() })
</script>

<style scoped>
.model-panel { flex-shrink: 0; padding: 8px 12px; border-bottom: 1px solid #e5e9f2; background: #f9fbff; color: #435170; }
.model-row { display: flex; align-items: center; gap: 6px; }
.model-row select { flex: 1; min-width: 0; }
.model-actions { margin-top: 7px; }
.model-panel select,.model-panel input { width: 100%; box-sizing: border-box; min-height: 32px; padding: 5px 7px; border: 1px solid #dbe2ef; border-radius: 6px; background: white; color: #34415e; font-size: 12px; }
.model-panel button { width: auto; flex-shrink: 0; margin: 0; padding: 5px 8px; border: 1px solid #dbe2ef; border-radius: 6px; background: white; color: #4053a6; font-size: 12px; line-height: 20px; }
.model-panel button:disabled { opacity: .5; }
.model-fields { display: grid; gap: 8px; margin-top: 8px; max-height: 245px; overflow-y: auto; }
.model-fields label { display: grid; gap: 3px; font-size: 12px; }
.model-hint,.model-note { margin: 5px 0 0; font-size: 11px; line-height: 1.45; }
.model-hint { color: #77839b; }
</style>
