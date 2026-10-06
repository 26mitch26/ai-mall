package com.ai.mall.portal.controller;

import com.ai.mall.common.api.CommonResult;
import com.ai.mall.portal.domain.OmsOrderReturnApplyParam;
import com.ai.mall.portal.service.OmsPortalOrderReturnApplyService;
import com.ai.mall.portal.service.UmsMemberService;
import com.ai.mall.model.UmsMember;
import com.ai.mall.model.OmsOrderReturnApply;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 退货申请管理Controller
 * Created by macro on 2018/10/17.
 */
@Controller
@Tag(name = "OmsPortalOrderReturnApplyController",description = "退货申请管理")
@RequestMapping("/returnApply")
public class OmsPortalOrderReturnApplyController {
    @Autowired
    private OmsPortalOrderReturnApplyService returnApplyService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UmsMemberService memberService;
    @Autowired
    private ObjectMapper objectMapper;

    @Operation(summary = "申请退货")
    @RequestMapping(value = "/create", method = RequestMethod.POST)
    @ResponseBody
    @Transactional
    public CommonResult<Map<String, Object>> create(@RequestBody OmsOrderReturnApplyParam returnApply,
                                                     @RequestHeader("Idempotency-Key") String operationId) {
        if (operationId == null || !operationId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效的幂等操作编号");
        }
        UmsMember member = memberService.getCurrentMember();
        if (member == null || member.getId() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "需要登录");
        String requestHash = requestHash(returnApply);
        jdbcTemplate.update("INSERT INTO agent_operation_idempotency (owner_type, owner_id, operation_key, request_hash, status, created_at, updated_at) " +
                        "VALUES ('after_sale', ?, ?, ?, 'PROCESSING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) " +
                        "ON DUPLICATE KEY UPDATE id = id",
                String.valueOf(member.getId()), operationId, requestHash);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT request_hash, status, result_json FROM agent_operation_idempotency " +
                        "WHERE owner_type='after_sale' AND owner_id=? AND operation_key=? FOR UPDATE",
                String.valueOf(member.getId()), operationId);
        if (rows.isEmpty()) throw new IllegalStateException("售后幂等记录无法读取");
        Map<String, Object> row = rows.get(0);
        if (!requestHash.equals(row.get("request_hash"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "该操作编号已用于不同售后参数");
        if ("COMPLETED".equals(String.valueOf(row.get("status")))) {
            return CommonResult.success(readResult(row.get("result_json")));
        }
        OmsOrderReturnApply application = returnApplyService.createAndReturn(returnApply);
        if (application == null || application.getId() == null) throw new IllegalStateException("售后申请未成功创建");
        Map<String, Object> result = new HashMap<>();
        result.put("count", 1);
        result.put("returnApplyId", application.getId());
        result.put("orderSn", application.getOrderSn());
        try {
            jdbcTemplate.update("UPDATE agent_operation_idempotency SET status='COMPLETED', result_json=?, updated_at=CURRENT_TIMESTAMP " +
                            "WHERE owner_type='after_sale' AND owner_id=? AND operation_key=?",
                    objectMapper.writeValueAsString(result), String.valueOf(member.getId()), operationId);
        } catch (Exception e) { throw new IllegalStateException("售后结果无法持久化", e); }
        return CommonResult.success(result);
    }

    @GetMapping("/operations/{operationId}")
    @ResponseBody
    public CommonResult<Map<String, Object>> lookupOperation(@PathVariable String operationId) {
        if (operationId == null || !operationId.matches("[A-Za-z0-9_-]{1,128}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效的操作编号");
        UmsMember member = memberService.getCurrentMember();
        if (member == null || member.getId() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "需要登录");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT status, result_json FROM agent_operation_idempotency WHERE owner_type='after_sale' AND owner_id=? AND operation_key=?",
                String.valueOf(member.getId()), operationId);
        Map<String, Object> data = new HashMap<>();
        if (rows.isEmpty()) {
            data.put("status", "NOT_FOUND"); data.put("result", null);
        } else {
            Map<String, Object> row = rows.get(0);
            String status = String.valueOf(row.get("status"));
            data.put("status", status);
            data.put("result", "COMPLETED".equals(status) && row.get("result_json") != null ? readResult(row.get("result_json")) : null);
        }
        return CommonResult.success(data);
    }

    private String requestHash(OmsOrderReturnApplyParam request) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "售后参数不能为空");
        try {
            Map<String, Object> values = objectMapper.convertValue(request, new TypeReference<>() { });
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(objectMapper.writeValueAsBytes(new TreeMap<>(values)));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "售后参数无效"); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readResult(Object raw) {
        try { return objectMapper.readValue(String.valueOf(raw), new TypeReference<>() { }); }
        catch (Exception e) { throw new IllegalStateException("售后幂等结果损坏", e); }
    }
}
