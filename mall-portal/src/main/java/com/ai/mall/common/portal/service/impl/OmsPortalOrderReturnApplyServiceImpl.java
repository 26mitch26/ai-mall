package com.ai.mall.portal.service.impl;

import com.ai.mall.mapper.OmsOrderReturnApplyMapper;
import com.ai.mall.mapper.OmsOrderMapper;
import com.ai.mall.mapper.OmsOrderItemMapper;
import com.ai.mall.model.*;
import com.ai.mall.portal.domain.OmsOrderReturnApplyParam;
import com.ai.mall.portal.service.OmsPortalOrderReturnApplyService;
import com.ai.mall.portal.service.UmsMemberService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.Date;
import java.util.List;

/**
 * 订单退货管理Service实现类
 * Created by macro on 2018/10/17.
 */
@Service
public class OmsPortalOrderReturnApplyServiceImpl implements OmsPortalOrderReturnApplyService {
    @Autowired
    private OmsOrderReturnApplyMapper returnApplyMapper;
    @Autowired
    private OmsOrderMapper orderMapper;
    @Autowired
    private OmsOrderItemMapper orderItemMapper;
    @Autowired
    private UmsMemberService memberService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Override
    @Transactional
    public int create(OmsOrderReturnApplyParam returnApply) {
        return createAndReturn(returnApply) == null ? 0 : 1;
    }

    @Override
    @Transactional
    public OmsOrderReturnApply createAndReturn(OmsOrderReturnApplyParam returnApply) {
        if (returnApply == null || returnApply.getOrderId() == null) throw new IllegalArgumentException("订单信息无效");
        UmsMember current = memberService.getCurrentMember();
        if (current == null || current.getId() == null) throw new SecurityException("需要登录后申请售后");
        OmsOrder order = orderMapper.selectByPrimaryKey(returnApply.getOrderId());
        if (order == null || !current.getId().equals(order.getMemberId())
                || order.getOrderSn() == null || !order.getOrderSn().equalsIgnoreCase(returnApply.getOrderSn())) {
            throw new SecurityException("订单不存在或不属于当前会员");
        }
        // Serialize refund reservations per order. Pending/in-progress/completed applications reserve
        // the line's paid amount; rejected applications release it.
        try {
            jdbcTemplate.queryForMap("SELECT id FROM oms_order WHERE id=? AND member_id=? FOR UPDATE",
                    order.getId(), current.getId());
        } catch (org.springframework.dao.EmptyResultDataAccessException ex) {
            throw new SecurityException("订单不存在或不属于当前会员");
        }
        OmsOrderItemExample itemExample = new OmsOrderItemExample();
        itemExample.createCriteria().andOrderIdEqualTo(order.getId());
        List<OmsOrderItem> items = orderItemMapper.selectByExample(itemExample);
        if (CollectionUtils.isEmpty(items)) throw new IllegalArgumentException("订单没有可申请售后的商品");
        OmsOrderItem item = items.stream().filter(candidate -> candidate.getProductId() != null
                && candidate.getProductId().equals(returnApply.getProductId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("申请商品不属于该订单"));
        int quantity = item.getProductQuantity() == null ? 1 : item.getProductQuantity();
        java.math.BigDecimal paidUnitPrice = item.getRealAmount() == null ? java.math.BigDecimal.ZERO : item.getRealAmount();
        java.math.BigDecimal requestedRefund = paidUnitPrice.multiply(java.math.BigDecimal.valueOf(quantity));
        java.math.BigDecimal alreadyReservedForProduct = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(return_amount), 0) FROM oms_order_return_apply WHERE order_id=? AND product_id=? AND status IN (0,1,2)",
                java.math.BigDecimal.class, order.getId(), item.getProductId());
        java.math.BigDecimal alreadyReservedForOrder = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(return_amount), 0) FROM oms_order_return_apply WHERE order_id=? AND status IN (0,1,2)",
                java.math.BigDecimal.class, order.getId());
        if (alreadyReservedForProduct == null) alreadyReservedForProduct = java.math.BigDecimal.ZERO;
        if (alreadyReservedForOrder == null) alreadyReservedForOrder = java.math.BigDecimal.ZERO;
        java.math.BigDecimal productBalance = requestedRefund.subtract(alreadyReservedForProduct).max(java.math.BigDecimal.ZERO);
        java.math.BigDecimal orderBalance = (order.getPayAmount() == null ? java.math.BigDecimal.ZERO : order.getPayAmount())
                .subtract(alreadyReservedForOrder).max(java.math.BigDecimal.ZERO);
        java.math.BigDecimal availableRefund = productBalance.min(orderBalance);
        if (requestedRefund.compareTo(java.math.BigDecimal.ZERO) <= 0 || requestedRefund.compareTo(availableRefund) > 0) {
            throw new IllegalArgumentException("申请退款金额超过该订单剩余可退金额");
        }

        OmsOrderReturnApply realApply = new OmsOrderReturnApply();
        BeanUtils.copyProperties(returnApply, realApply);
        realApply.setOrderId(order.getId());
        realApply.setOrderSn(order.getOrderSn());
        realApply.setMemberUsername(current.getUsername());
        realApply.setReturnName(order.getReceiverName());
        realApply.setReturnPhone(order.getReceiverPhone());
        realApply.setReturnAmount(requestedRefund);
        realApply.setProductId(item.getProductId());
        realApply.setProductName(item.getProductName());
        realApply.setProductPic(item.getProductPic());
        realApply.setProductCount(quantity);
        realApply.setProductPrice(item.getProductPrice());
        realApply.setProductRealPrice(paidUnitPrice);
        realApply.setProductBrand(item.getProductBrand());
        realApply.setProductAttr(item.getProductAttr());
        realApply.setCreateTime(new Date());
        realApply.setStatus(0);
        return returnApplyMapper.insert(realApply) > 0 ? realApply : null;
    }
}
