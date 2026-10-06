package com.ai.mall.portal.controller;

import com.ai.mall.common.api.CommonPage;
import com.ai.mall.common.api.CommonResult;
import com.ai.mall.portal.domain.ConfirmOrderResult;
import com.ai.mall.portal.domain.OmsOrderDetail;
import com.ai.mall.portal.domain.OrderParam;
import com.ai.mall.portal.service.UmsMemberService;
import com.ai.mall.portal.service.OmsPortalOrderService;
import com.ai.mall.model.UmsMember;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;

/**
 * 订单管理Controller
 * Created by macro on 2018/8/30.
 */
@Controller
@Tag(name = "OmsPortalOrderController", description = "订单管理")
@RequestMapping("/order")
public class OmsPortalOrderController {
    @Autowired
    private OmsPortalOrderService portalOrderService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UmsMemberService memberService;
    @Autowired
    private ObjectMapper objectMapper;

    @Operation(summary = "获取下单幂等token，防重复提交")
    @RequestMapping(value = "/token", method = RequestMethod.GET)
    @ResponseBody
    public CommonResult<String> getIdempotencyToken() {
        return CommonResult.success(UUID.randomUUID().toString());
    }

    @Operation(summary = "根据购物车信息生成确认单")
    @RequestMapping(value = "/generateConfirmOrder", method = RequestMethod.POST)
    @ResponseBody
    public CommonResult<ConfirmOrderResult> generateConfirmOrder(@RequestBody List<Long> cartIds) {
        ConfirmOrderResult confirmOrderResult = portalOrderService.generateConfirmOrder(cartIds);
        return CommonResult.success(confirmOrderResult);
    }

    @Operation(summary = "根据购物车信息生成订单")
    @RequestMapping(value = "/generateOrder", method = RequestMethod.POST)
    @ResponseBody
    @Transactional
    public CommonResult generateOrder(@RequestBody OrderParam orderParam) {
        String token = orderParam == null ? null : orderParam.getIdempotencyToken();
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少幂等键，请先调用 GET /order/token 获取");
        }
        if (token.length() > 128 || !token.matches("[A-Za-z0-9_-]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效的幂等键");
        }
        UmsMember member = memberService.getCurrentMember();
        if (member == null || member.getId() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "需要登录后下单");
        String requestHash = requestHash(orderParam);
        // Insert-if-absent plus row lock serializes concurrent retries. The order and stored response
        // commit in the same database transaction, so a retry after a timeout returns the first result.
        jdbcTemplate.update("INSERT INTO agent_operation_idempotency (owner_type, owner_id, operation_key, request_hash, status, created_at, updated_at) " +
                        "VALUES ('order', ?, ?, ?, 'PROCESSING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) " +
                        "ON DUPLICATE KEY UPDATE id = id",
                String.valueOf(member.getId()), token.trim(), requestHash);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT request_hash, status, result_json FROM agent_operation_idempotency " +
                        "WHERE owner_type='order' AND owner_id=? AND operation_key=? FOR UPDATE",
                String.valueOf(member.getId()), token.trim());
        if (rows.isEmpty()) throw new IllegalStateException("幂等请求记录无法读取");
        Map<String, Object> row = rows.get(0);
        if (!requestHash.equals(row.get("request_hash"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该幂等键已用于不同的下单参数");
        }
        String status = String.valueOf(row.get("status"));
        if ("COMPLETED".equals(status)) {
            try {
                @SuppressWarnings("unchecked") Map<String, Object> prior = objectMapper.readValue(String.valueOf(row.get("result_json")), Map.class);
                return CommonResult.success(prior, "下单成功");
            } catch (Exception e) { throw new IllegalStateException("幂等请求结果损坏", e); }
        }
        Map<String, Object> result = portalOrderService.generateOrder(orderParam);
        try {
            jdbcTemplate.update("UPDATE agent_operation_idempotency SET status='COMPLETED', result_json=?, updated_at=CURRENT_TIMESTAMP " +
                            "WHERE owner_type='order' AND owner_id=? AND operation_key=?",
                    objectMapper.writeValueAsString(result), String.valueOf(member.getId()), token.trim());
        } catch (Exception e) { throw new IllegalStateException("下单结果无法持久化", e); }
        return CommonResult.success(result, "下单成功");
    }

    private String requestHash(OrderParam p) {
        try {
            Map<String, Object> canonical = new java.util.TreeMap<>();
            canonical.put("addressId", p.getMemberReceiveAddressId());
            canonical.put("couponId", p.getCouponId());
            canonical.put("useIntegration", p.getUseIntegration());
            canonical.put("payType", p.getPayType());
            List<Long> cartIds = p.getCartIds() == null ? List.of() : new ArrayList<>(p.getCartIds());
            cartIds.sort(Comparator.nullsFirst(Long::compareTo));
            canonical.put("cartIds", cartIds);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(objectMapper.writeValueAsBytes(canonical));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) { throw new IllegalStateException("下单参数无法校验", e); }
    }

    @Operation(summary = "按幂等操作编号查询下单结果")
    @GetMapping("/operations/{operationId}")
    @ResponseBody
    public CommonResult<Map<String, Object>> lookupOrderOperation(@PathVariable String operationId) {
        if (operationId == null || !operationId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效的操作编号");
        }
        UmsMember member = memberService.getCurrentMember();
        if (member == null || member.getId() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "需要登录");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT status, result_json FROM agent_operation_idempotency WHERE owner_type='order' AND owner_id=? AND operation_key=?",
                String.valueOf(member.getId()), operationId);
        Map<String, Object> data = new java.util.HashMap<>();
        if (rows.isEmpty()) {
            data.put("status", "NOT_FOUND");
            data.put("result", null);
        } else {
            Map<String, Object> row = rows.get(0);
            String status = String.valueOf(row.get("status"));
            data.put("status", status);
            if ("COMPLETED".equals(status) && row.get("result_json") != null) {
                try { data.put("result", objectMapper.readValue(String.valueOf(row.get("result_json")), Map.class)); }
                catch (Exception e) { throw new IllegalStateException("幂等结果损坏", e); }
            } else data.put("result", null);
        }
        return CommonResult.success(data);
    }

    @Operation(summary = "用户支付成功的回调")
    @RequestMapping(value = "/paySuccess", method = RequestMethod.POST)
    @ResponseBody
    public CommonResult paySuccess(@RequestParam Long orderId,@RequestParam Integer payType) {
        Integer count = portalOrderService.paySuccess(orderId,payType);
        return CommonResult.success(count, "支付成功");
    }

    @Operation(summary = "自动取消超时订单")
    @RequestMapping(value = "/cancelTimeOutOrder", method = RequestMethod.POST)
    @ResponseBody
    public CommonResult cancelTimeOutOrder() {
        portalOrderService.cancelTimeOutOrder();
        return CommonResult.success(null);
    }

    @Operation(summary = "取消单个超时订单")
    @RequestMapping(value = "/cancelOrder", method = RequestMethod.POST)
    @ResponseBody
    public CommonResult cancelOrder(Long orderId) {
        portalOrderService.sendDelayMessageCancelOrder(orderId);
        return CommonResult.success(null);
    }

    @Operation(summary = "按状态分页获取用户订单列表")
    @Parameter(name = "status", description = "订单状态：-1->全部；0->待付款；1->待发货；2->已发货；3->已完成；4->已关闭",
            in = ParameterIn.QUERY, schema = @Schema(type = "integer",defaultValue = "-1",allowableValues = {"-1","0","1","2","3","4"}))
    @RequestMapping(value = "/list", method = RequestMethod.GET)
    @ResponseBody
    public CommonResult<CommonPage<OmsOrderDetail>> list(@RequestParam Integer status,
                                                   @RequestParam(required = false, defaultValue = "1") Integer pageNum,
                                                   @RequestParam(required = false, defaultValue = "5") Integer pageSize) {
        CommonPage<OmsOrderDetail> orderPage = portalOrderService.list(status,pageNum,pageSize);
        return CommonResult.success(orderPage);
    }

    @Operation(summary = "根据ID获取订单详情")
    @RequestMapping(value = "/detail/{orderId}", method = RequestMethod.GET)
    @ResponseBody
    public CommonResult<OmsOrderDetail> detail(@PathVariable Long orderId) {
        OmsOrderDetail orderDetail = portalOrderService.detail(orderId);
        return CommonResult.success(orderDetail);
    }

    @Operation(summary = "用户取消订单")
    @RequestMapping(value = "/cancelUserOrder", method = RequestMethod.POST)
    @ResponseBody
    public CommonResult cancelUserOrder(Long orderId) {
        portalOrderService.cancelOrder(orderId);
        return CommonResult.success(null);
    }

    @Operation(summary = "用户确认收货")
    @RequestMapping(value = "/confirmReceiveOrder", method = RequestMethod.POST)
    @ResponseBody
    public CommonResult confirmReceiveOrder(Long orderId) {
        portalOrderService.confirmReceiveOrder(orderId);
        return CommonResult.success(null);
    }

    @Operation(summary = "用户删除订单")
    @RequestMapping(value = "/deleteOrder", method = RequestMethod.POST)
    @ResponseBody
    public CommonResult deleteOrder(Long orderId) {
        portalOrderService.deleteOrder(orderId);
        return CommonResult.success(null);
    }
}
