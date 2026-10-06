<template>
  <view class="uni-numbox">
    <view class="uni-numbox-minus" @click="_calcValue('subtract')">
      <text class="yticon icon-jianhao" :class="minDisabled ? 'uni-numbox-disabled' : ''"></text>
    </view>
    <input
      class="uni-numbox-value"
      type="number"
      :disabled="disabled"
      :value="inputValue"
      @input="_onInput"
      @blur="_onBlur"
    />
    <view class="uni-numbox-plus" @click="_calcValue('add')">
      <text class="yticon icon-jia" :class="maxDisabled ? 'uni-numbox-disabled' : ''"></text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'

const props = defineProps<{
  isMax?: boolean
  isMin?: boolean
  index?: number
  value?: number
  min?: number
  max?: number
  step?: number
  disabled?: boolean
}>()

const emit = defineEmits<{
  eventChange: [{ number: number; index: number }]
}>()

const numericValue = ref(0)
const inputValue = ref('0')
const minDisabled = computed(() => Boolean(props.isMin) || numericValue.value <= (props.min ?? -Infinity))
const maxDisabled = computed(() => Boolean(props.isMax) || numericValue.value >= (props.max ?? Infinity))

const clampValue = (value: number) => Math.min(props.max ?? Infinity, Math.max(props.min ?? -Infinity, value))
const setValue = (value: number, notify: boolean) => {
  const nextValue = clampValue(value)
  if (!Number.isFinite(nextValue)) return
  const changed = nextValue !== numericValue.value
  numericValue.value = nextValue
  inputValue.value = String(nextValue)
  if (notify && changed) emit('eventChange', { number: nextValue, index: props.index ?? 0 })
}

watch(() => props.value, (value) => {
  setValue(typeof value === 'number' && Number.isFinite(value) ? value : props.min ?? 0, false)
}, { immediate: true })

const _calcValue = (type: 'subtract' | 'add') => {
  if (props.disabled) return
  const step = typeof props.step === 'number' && Number.isFinite(props.step) && props.step > 0 ? props.step : 1
  const scale = decimalScale([numericValue.value, step, props.min, props.max])
  const currentUnits = Math.round(numericValue.value * scale)
  const stepUnits = Math.round(step * scale)
  const next = (currentUnits + (type === 'add' ? stepUnits : -stepUnits)) / scale
  setValue(next, true)
}

const decimalScale = (values: Array<number | undefined>) => {
  const places = values.filter((value): value is number => typeof value === 'number' && Number.isFinite(value)).map((value) => {
    const [coefficient, exponentText] = value.toString().toLowerCase().split('e')
    const fractionLength = coefficient.split('.')[1]?.length ?? 0
    return Math.max(0, fractionLength - Number(exponentText || 0))
  })
  return 10 ** Math.min(12, Math.max(0, ...places))
}

const _onInput = (event: { detail: { value: string } }) => {
  inputValue.value = event.detail.value
}

const _onBlur = (event: { detail: { value: string } }) => {
  const rawValue = event.detail.value.trim()
  // An empty or invalid edit restores the last committed numeric value.
  if (!rawValue) { inputValue.value = String(numericValue.value); return }
  const parsed = Number(rawValue)
  if (!Number.isFinite(parsed)) { inputValue.value = String(numericValue.value); return }
  setValue(parsed, true)
}
</script>

<style scoped>
.uni-numbox {
  position: absolute;
  left: 30rpx;
  bottom: 0;
  display: flex;
  justify-content: flex-start;
  align-items: center;
  width: 230rpx;
  height: 70rpx;
  background: #f5f5f5;
}

.uni-numbox-minus,
.uni-numbox-plus {
  margin: 0;
  background-color: #f5f5f5;
  width: 70rpx;
  height: 100%;
  line-height: 70rpx;
  text-align: center;
  position: relative;
}

.uni-numbox-minus .yticon,
.uni-numbox-plus .yticon {
  font-size: 36rpx;
  color: #555;
}

.uni-numbox-minus {
  border-right: none;
  border-top-left-radius: 6rpx;
  border-bottom-left-radius: 6rpx;
}

.uni-numbox-plus {
  border-left: none;
  border-top-right-radius: 6rpx;
  border-bottom-right-radius: 6rpx;
}

.uni-numbox-value {
  position: relative;
  background-color: #f5f5f5;
  width: 90rpx;
  height: 50rpx;
  text-align: center;
  padding: 0;
  font-size: 30rpx;
}

.uni-numbox-disabled.yticon {
  color: #d6d6d6;
}
</style>
