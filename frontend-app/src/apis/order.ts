import { http } from '@/utils/http'
import type {
  ConfirmOrderResult,
  GenerateOrderResult,
  OrderParam,
  OmsOrderDetail,
  OmsOrderReturnApplyParam,
} from '@/types/order'
import type { CommonPage, PageParam } from '@/types/common'

/** Keep the same operation key after an uncertain network response, including page reloads. */
const operationKey = (kind: string, data: unknown) => {
  const storageKey = `mall-operation:${kind}:${JSON.stringify(data)}`
  let key = uni.getStorageSync(storageKey) as string
  if (!key) {
    key = `${kind}-${Date.now()}-${Math.random().toString(36).slice(2)}-${Math.random().toString(36).slice(2)}`
    uni.setStorageSync(storageKey, key)
  }
  return { key, storageKey }
}

/** 生成确认单信息 */
export const generateConfirmOrderAPI = (cartIds: number[]) => {
  return http<ConfirmOrderResult>({
    method: 'POST',
    url: '/order/generateConfirmOrder',
    data: cartIds,
  })
}

/** 生成订单 */
export const generateOrderAPI = async (data: OrderParam) => {
  const operation = operationKey('order', { ...data, cartIds: [...(data.cartIds || [])].sort((a, b) => a - b) })
  const result = await http<GenerateOrderResult>({
    method: 'POST',
    url: '/order/generateOrder',
    data: { ...data, idempotencyToken: data.idempotencyToken || operation.key },
  })
  uni.removeStorageSync(operation.storageKey)
  return result
}

/** 按状态分页获取用户订单列表 */
export const getOrderListAPI = (params: PageParam & { status: number }) => {
  return http<CommonPage<OmsOrderDetail>>({
    method: 'GET',
    url: '/order/list',
    params,
  })
}

/** 根据ID获取订单详情 */
export const getOrderDetailAPI = (orderId: number) => {
  return http<OmsOrderDetail>({
    method: 'GET',
    url: `/order/detail/${orderId}`,
  })
}

/** 用户取消订单 */
export const cancelUserOrderAPI = (orderId: number) => {
  return http({
    method: 'POST',
    url: '/order/cancelUserOrder',
    params: { orderId },
  })
}

/** 用户确认收货 */
export const confirmReceiveOrderAPI = (orderId: number) => {
  return http({
    method: 'POST',
    url: '/order/confirmReceiveOrder',
    params: { orderId },
  })
}

/** 用户删除订单 */
export const deleteOrderAPI = (orderId: number) => {
  return http({
    method: 'POST',
    url: '/order/deleteOrder',
    params: { orderId },
  })
}

/** 支付成功回调 */
export const payOrderSuccessAPI = (params: { orderId: number; payType: number }) => {
  return http({
    method: 'POST',
    url: '/order/paySuccess',
    params,
  })
}

/** 支付宝交易状态查询 */
export const fetchAliapyStatusAPI = (params: { outTradeNo: string }) => {
  return http<string>({
    method: 'GET',
    url: '/alipay/query',
    params,
  })
}

/** 申请退货 */
export const createReturnApplyAPI = async (data: OmsOrderReturnApplyParam) => {
  const operation = operationKey('after-sale', data)
  const result = await http({
    method: 'POST',
    url: '/returnApply/create',
    data,
    header: { 'Idempotency-Key': operation.key },
  })
  uni.removeStorageSync(operation.storageKey)
  return result
}
