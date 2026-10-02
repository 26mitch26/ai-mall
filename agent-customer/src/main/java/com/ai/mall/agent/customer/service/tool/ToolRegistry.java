package com.ai.mall.agent.customer.service.tool;

import com.ai.mall.agent.customer.model.Tool;
import com.ai.mall.agent.customer.model.ToolInvocationContext;
import com.ai.mall.agent.customer.service.security.InputSanitizer;
import com.ai.mall.agent.customer.service.security.ToolAccessGuard;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册表与执行入口
 * <p>
 * 安全约束（本次加固）：
 * 1. 调用前鉴权：由 {@link ToolAccessGuard} 判定工具等级与用户身份，
 *    敏感工具未登录即拒绝（fail-closed），未登记的工具名一律拒绝。
 * 2. 数据隔离：订单查询/售后工单都先通过带用户 JWT 的会员订单接口解析订单，
 *    本地再校验订单归属（memberId 一致），越权订单按"未找到"处理。
 * 3. 服务间鉴权：调用后端接口时携带内部服务令牌与用户 JWT，
 *    令牌只从配置/环境变量读取，禁止硬编码。
 * 4. 返回净化：工具返回属于外部数据，进入提示词前必须过一遍 {@link InputSanitizer}，
 *    防止间接提示词注入（Indirect Prompt Injection）。
 * 5. 错误不外泄：异常只返回通用文案，不把堆栈与内部信息回传给模型/用户。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolRegistry {

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final RestTemplate restTemplate;
    private final ToolAccessGuard toolAccessGuard;
    private final InputSanitizer inputSanitizer;

    @Value("${service.mall-search.url:http://localhost:8081}")
    private String mallSearchUrl;

    @Value("${service.mall-portal.url:http://localhost:8087}")
    private String mallPortalUrl;

    /**
     * 内部服务调用令牌，仅从配置或环境变量注入，未配置时不发送该请求头。
     */
    @Value("${service.internal.token:}")
    private String internalServiceToken;

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    @PostConstruct
    public void init() {
        registerTool(Tool.builder()
                .name("search_products")
                .description("搜索商品信息，返回商品列表")
                .parameters("{\"keyword\": \"搜索关键词\", \"category\": \"分类\", \"page\": \"页码\"}")
                .executor((params, context) -> searchProducts(params, context))
                .build());

        registerTool(Tool.builder()
                .name("get_order_info")
                .description("查询订单信息，返回订单详情")
                .parameters("{\"order_sn\": \"订单编号\"}")
                .executor((params, context) -> getOrderInfo(params, context))
                .build());

        registerTool(Tool.builder()
                .name("create_after_sale")
                .description("创建售后工单")
                .parameters("{\"order_sn\": \"订单编号\", \"reason\": \"原因\", \"description\": \"描述\"}")
                .executor((params, context) -> createAfterSale(params, context))
                .build());

        // "我买了什么/我的订单/购买记录"类问题直接列订单：此前只有按订单号查详情的工具，
        // 这类问题只能引导用户提供编号，用户反馈"我账号下买了什么他查不出来"（实测缺口）。
        registerTool(Tool.builder()
                .name("list_my_orders")
                .description("查询当前登录会员的订单列表（用于\"我买了什么/我的订单/购买记录\"类问题），返回最近订单及商品明细")
                .parameters("{\"status\": \"订单状态(可选：-1全部 0待付款 1待发货 2已发货 3已完成 4已关闭)\", \"pageSize\": \"返回条数(默认5)\"}")
                .executor((params, context) -> listMyOrders(params, context))
                .build());

        // 会员明确要求购买时创建真实订单（加入购物车 → 取默认地址 → 幂等 token → 生成订单）。
        // 写操作：鉴权等级 USER_WRITE（必须登录且携带用户令牌）。
        registerTool(Tool.builder()
                .name("place_order")
                .description("为当前登录会员创建订单（用户明确要求购买某商品时使用），返回订单号与应付金额")
                .parameters("{\"product_id\": \"商品ID(必填)\", \"quantity\": \"购买数量(默认1)\"}")
                .executor((params, context) -> placeOrder(params, context))
                .build());

        // 取消待付款订单（mall-portal 仅允许状态=待付款的订单取消，取消后状态=已关闭并释放库存锁）。
        // 写操作：鉴权等级 USER_WRITE（必须登录且携带用户令牌）。
        registerTool(Tool.builder()
                .name("cancel_order")
                .description("取消当前登录会员的待付款订单（仅待付款状态可取消），取消后订单进入已关闭")
                .parameters("{\"order_sn\": \"订单编号(必填)\"}")
                .executor((params, context) -> cancelOrder(params, context))
                .build());
    }

    public void registerTool(Tool tool) {
        tools.put(tool.getName(), tool);
        log.info("Registered tool: {}", tool.getName());
    }

    public Tool getTool(String name) {
        return tools.get(name);
    }

    public List<Tool> getAllTools() {
        return new ArrayList<>(tools.values());
    }

    /**
     * 无身份上下文执行工具（兼容入口）。敏感工具会在此被拒绝。
     */
    public String executeTool(String name, String parameters) {
        return executeTool(name, parameters, null);
    }

    /**
     * 执行工具（主入口）
     *
     * @param name       工具名
     * @param parameters 参数 JSON
     * @param context    调用上下文（含用户身份）
     * @return 净化后的工具返回内容；被拒绝或异常时返回结构化错误信息
     */
    public String executeTool(String name, String parameters, ToolInvocationContext context) {
        ToolAccessGuard.Decision decision = toolAccessGuard.authorize(name, context);
        if (!decision.isAllowed()) {
            log.warn("工具调用被拒绝: tool={}, reason={}", name, decision.getReason());
            return "{\"error\": \"" + decision.getReason() + "\", \"blocked\": true}";
        }

        Tool tool = tools.get(name);
        if (tool == null) {
            return "Tool not found: " + name;
        }

        try {
            String rawResult = tool.getExecutor().execute(parameters, context);
            // 外部数据进入提示词前统一净化，阻断间接提示词注入
            return inputSanitizer.sanitizeToolObservation(rawResult);
        } catch (Exception e) {
            // 不回显内部异常细节，避免信息外泄
            log.error("Error executing tool {}: {}", name, e.getMessage());
            return "{\"error\": \"工具执行失败，请稍后重试\"}";
        }
    }

    /**
     * 执行需要继续按 JSON 解析的公开工具。普通 ReAct 链路仍使用 executeTool 的
     * 长度限制与注入净化；商品售前链路先解析本地 API 返回，再只把商品字段交给回答层，
     * 因此不能把被截断的 JSON 当作完整文档再次解析。
     */
    public String executeStructuredTool(String name, String parameters, ToolInvocationContext context) {
        ToolAccessGuard.Decision decision = toolAccessGuard.authorize(name, context);
        if (!decision.isAllowed()) {
            return "{\"error\": \"" + decision.getReason() + "\", \"blocked\": true}";
        }
        Tool tool = tools.get(name);
        if (tool == null) return "{\"error\": \"工具不存在\"}";
        try {
            return tool.getExecutor().execute(parameters, context);
        } catch (Exception e) {
            log.error("Error executing structured tool {}: {}", name, e.getMessage());
            return "{\"error\": \"工具执行失败，请稍后重试\"}";
        }
    }

    // ==================== 具体工具实现 ====================

    private String searchProducts(String params, ToolInvocationContext context) {
        try {
            Map<String, Object> paramMap = objectMapper.readValue(params, new TypeReference<>() {});
            String keyword = (String) paramMap.getOrDefault("keyword", "");
            Integer pageNum = paramMap.containsKey("page") ? Integer.parseInt(paramMap.get("page").toString()) : 1;
            Integer pageSize = paramMap.containsKey("pageSize") ? Integer.parseInt(paramMap.get("pageSize").toString()) : 5;

            // 商品搜索走商城前台公开 API，任务型对话可以在未登录时完成售前咨询；
            // 订单与售后仍走 mall-portal 的用户鉴权接口。
            String url = mallPortalUrl + "/product/search?keyword={keyword}&pageNum={pageNum}&pageSize={pageSize}";
            Map<String, Object> uriVariables = new HashMap<>();
            uriVariables.put("keyword", keyword);
            uriVariables.put("pageNum", pageNum - 1);
            uriVariables.put("pageSize", pageSize);

            // 这是商城公开的售前商品搜索，不能把后台管理员 JWT 转发给 portal；
            // 管理员令牌会被会员端判定为无效而返回 401。
            return getWithAuth(url, uriVariables, null);
        } catch (Exception e) {
            log.error("搜索商品失败: {}", e.getMessage());
            return "{\"error\": \"搜索商品失败\"}";
        }
    }

    private String getOrderInfo(String params, ToolInvocationContext context) {
        try {
            Map<String, Object> paramMap = objectMapper.readValue(params, new TypeReference<>() {});
            String orderSn = paramMap.get("order_sn") == null ? null : paramMap.get("order_sn").toString().trim();

            if (orderSn == null || orderSn.isEmpty()) {
                return "{\"error\": \"订单编号不能为空\"}";
            }

            JsonNode order = resolveOrder(orderSn, context);
            if (order == null) {
                return "{\"error\": \"未找到订单 " + orderSn + "，请确认订单编号是否正确\"}";
            }

            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("message", "操作成功");
            result.put("data", order);
            log.info("Query order completed for memberId={}, orderSn={}", context.getMemberId(), orderSn);
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("查询订单信息失败: {}", e.getMessage());
            return "{\"error\": \"查询订单信息失败\"}";
        }
    }

    /**
     * 查询当前登录会员的订单列表（含商品明细）。
     * <p>
     * mall-portal 的 /order/list 按登录会员过滤（memberId 来自用户令牌），
     * 返回的 CommonPage 已含 orderItemList，可直接回答"我买了哪些东西"。
     */
    private String listMyOrders(String params, ToolInvocationContext context) {
        try {
            Map<String, Object> paramMap = objectMapper.readValue(params, new TypeReference<>() {});
            int status = paramMap.get("status") == null ? -1 : Integer.parseInt(paramMap.get("status").toString());
            int pageSize = paramMap.get("pageSize") == null ? 5 : Integer.parseInt(paramMap.get("pageSize").toString());
            if (pageSize < 1 || pageSize > 50) {
                pageSize = 5;
            }

            String url = mallPortalUrl + "/order/list?status={status}&pageNum=1&pageSize={pageSize}";
            Map<String, Object> uriVariables = new HashMap<>();
            uriVariables.put("status", status);
            uriVariables.put("pageSize", pageSize);
            String response = getWithAuth(url, uriVariables, context);
            log.info("List orders completed for memberId={}", context.getMemberId());
            return response;
        } catch (Exception e) {
            log.error("查询订单列表失败: {}", e.getMessage());
            return "{\"error\": \"查询订单列表失败\"}";
        }
    }

    /**
     * 为当前登录会员创建订单：商品快照 → 加入购物车 → 默认收货地址 → 幂等 token → 生成订单。
     * <p>
     * 写操作全程使用登录会员的令牌（mall-portal 按令牌解析会员身份），
     * 订单创建后处于"待付款"状态，用户在会员端"我的订单"中完成支付。
     */
    private String placeOrder(String params, ToolInvocationContext context) {
        try {
            Map<String, Object> paramMap = objectMapper.readValue(params, new TypeReference<>() {});
            Long productId = paramMap.get("product_id") == null ? null : Long.parseLong(paramMap.get("product_id").toString());
            int quantity = paramMap.get("quantity") == null ? 1 : Integer.parseInt(paramMap.get("quantity").toString());
            if (productId == null || productId <= 0) {
                return "{\"error\": \"商品ID不能为空\"}";
            }
            if (quantity < 1 || quantity > 10) {
                quantity = 1;
            }

            // 1. 商品快照与 SKU：购物车条目与订单明细需要商品名/图/价，库存不足直接拒绝
            String detailResponse = getWithAuth(mallPortalUrl + "/product/detail/{productId}",
                    Map.of("productId", productId), null);
            JsonNode data = objectMapper.readTree(detailResponse).path("data");
            JsonNode product = data.path("product");
            if (product.isMissingNode() || product.isNull()) {
                return "{\"error\": \"商品不存在\"}";
            }
            JsonNode skus = data.path("skuStockList");
            Long skuId = null;
            int stock = 0;
            if (skus.isArray() && !skus.isEmpty()) {
                skuId = skus.get(0).path("id").asLong(0);
                stock = skus.get(0).path("stock").asInt(0);
            }
            if (stock < quantity) {
                return "{\"error\": \"库存不足，当前库存 " + stock + " 件\"}";
            }

            // 2. 加入购物车（mall-portal 按登录会员写入，重复商品自动累加）
            Map<String, Object> cartItem = new HashMap<>();
            cartItem.put("productId", productId);
            cartItem.put("productSkuId", skuId);
            cartItem.put("quantity", quantity);
            cartItem.put("productName", product.path("name").asText(""));
            cartItem.put("productPic", product.path("pic").asText(""));
            cartItem.put("price", product.path("price").asDouble(0));
            JsonNode cartResult = objectMapper.readTree(postWithAuth(mallPortalUrl + "/cart/add", cartItem, context));
            if (cartResult.path("code").asInt(500) != 200) {
                return "{\"error\": \"加入购物车失败\"}";
            }

            // 3. 定位购物车条目 ID（generateOrder 按 cartIds 下单）
            long cartId = 0;
            JsonNode cartList = objectMapper.readTree(getWithAuth(mallPortalUrl + "/cart/list", Map.of(), context));
            if (cartList.path("data").isArray()) {
                for (JsonNode item : cartList.path("data")) {
                    if (item.path("productId").asLong(0) == productId) {
                        cartId = item.path("id").asLong(0);
                        break;
                    }
                }
            }
            if (cartId == 0) {
                return "{\"error\": \"加入购物车失败（未找到购物车条目）\"}";
            }

            // 4. 收货地址：默认地址优先，无地址时引导用户先添加
            long addressId = 0;
            String receiver = "";
            JsonNode addresses = objectMapper.readTree(getWithAuth(mallPortalUrl + "/member/address/list", Map.of(), context)).path("data");
            if (addresses.isArray()) {
                JsonNode chosen = null;
                for (JsonNode address : addresses) {
                    if (address.path("defaultStatus").asInt(0) == 1) {
                        chosen = address;
                        break;
                    }
                }
                if (chosen == null && !addresses.isEmpty()) {
                    chosen = addresses.get(0);
                }
                if (chosen != null) {
                    addressId = chosen.path("id").asLong(0);
                    receiver = chosen.path("name").asText("");
                }
            }
            if (addressId == 0) {
                return "{\"error\": \"未找到收货地址，请先在'我的-地址管理'中添加收货地址\"}";
            }

            // 5. 幂等 token（防重复提交）+ 生成订单
            String idempotencyToken = objectMapper.readTree(
                    getWithAuth(mallPortalUrl + "/order/token", Map.of(), context)).path("data").asText("");
            Map<String, Object> orderBody = new HashMap<>();
            orderBody.put("memberReceiveAddressId", addressId);
            orderBody.put("cartIds", List.of(cartId));
            orderBody.put("idempotencyToken", idempotencyToken);
            JsonNode orderRoot = objectMapper.readTree(postWithAuth(mallPortalUrl + "/order/generateOrder", orderBody, context));
            if (orderRoot.path("code").asInt(500) != 200) {
                String message = orderRoot.path("message").asText("下单失败");
                return "{\"error\": \"" + message + "\"}";
            }
            JsonNode order = orderRoot.path("data").path("order");

            Map<String, Object> info = new HashMap<>();
            info.put("orderSn", order.path("orderSn").asText(""));
            info.put("orderId", order.path("id").asLong(0));
            info.put("payAmount", order.path("payAmount").asDouble(0));
            info.put("productName", product.path("name").asText(""));
            info.put("quantity", quantity);
            info.put("receiverName", receiver);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("message", "操作成功");
            result.put("data", info);
            log.info("Place order completed for memberId={}, orderSn={}", context.getMemberId(), info.get("orderSn"));
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("下单失败: {}", e.getMessage());
            return "{\"error\": \"下单失败，请稍后重试\"}";
        }
    }

    /**
     * 取消当前登录会员的待付款订单。
     * <p>
     * mall-portal 的取消接口只对"待付款"状态生效（取消后状态=已关闭并释放库存锁），
     * 因此这里先解析订单（含归属校验）并检查状态，非待付款直接给出原因而不是静默失败。
     */
    private String cancelOrder(String params, ToolInvocationContext context) {
        try {
            Map<String, Object> paramMap = objectMapper.readValue(params, new TypeReference<>() {});
            String orderSn = paramMap.get("order_sn") == null ? null : paramMap.get("order_sn").toString().trim();
            if (orderSn == null || orderSn.isEmpty()) {
                return "{\"error\": \"订单编号不能为空\"}";
            }

            JsonNode order = resolveOrder(orderSn, context);
            if (order == null) {
                return "{\"error\": \"未找到订单 " + orderSn + "，请确认订单编号是否正确\"}";
            }
            int status = order.path("status").asInt(-1);
            if (status != 0) {
                return "{\"error\": \"订单当前状态为" + orderStatusText(status) + "，仅待付款订单可取消\"}";
            }

            String response = postWithAuth(
                    mallPortalUrl + "/order/cancelUserOrder?orderId=" + order.path("id").asLong(0), null, context);
            JsonNode root = objectMapper.readTree(response);
            if (root.path("code").asInt(500) != 200) {
                return "{\"error\": \"" + root.path("message").asText("取消失败") + "\"}";
            }

            Map<String, Object> info = new HashMap<>();
            info.put("orderSn", orderSn);
            info.put("status", 4);
            info.put("statusText", "已关闭");
            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("message", "操作成功");
            result.put("data", info);
            log.info("Cancel order completed for memberId={}, orderSn={}", context.getMemberId(), orderSn);
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("取消订单失败: {}", e.getMessage());
            return "{\"error\": \"取消订单失败，请稍后重试\"}";
        }
    }

    /** 订单状态码 → 中文文案（工具返回值中的状态解释） */
    private String orderStatusText(int status) {
        return switch (status) {
            case 0 -> "待付款";
            case 1 -> "待发货";
            case 2 -> "已发货";
            case 3 -> "已完成";
            case 4 -> "已关闭";
            default -> "未知";
        };
    }

    private String createAfterSale(String params, ToolInvocationContext context) {
        try {
            Map<String, Object> paramMap = objectMapper.readValue(params, new TypeReference<>() {});
            String orderSn = paramMap.get("order_sn") == null ? null : paramMap.get("order_sn").toString().trim();

            if (orderSn == null || orderSn.isEmpty()) {
                return "{\"error\": \"订单编号不能为空\"}";
            }

            // 先解析订单，确认归属并获取订单明细，避免为他人或虚构订单创建工单
            JsonNode order = resolveOrder(orderSn, context);
            if (order == null) {
                return "{\"error\": \"未找到订单 " + orderSn + "，请确认订单编号是否正确\"}";
            }

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("orderId", order.path("id").asLong());
            requestBody.put("orderSn", order.path("orderSn").asText(orderSn));
            requestBody.put("memberUsername", order.path("memberUsername").asText(""));
            requestBody.put("returnName", order.path("receiverName").asText(""));
            requestBody.put("returnPhone", order.path("receiverPhone").asText(""));
            requestBody.put("returnAmount", order.path("payAmount").asDouble(0));
            requestBody.put("reason", paramMap.get("reason"));
            requestBody.put("description", paramMap.get("description"));

            // 用订单明细补全退货商品信息，后台退货申请页才能直接审核处理
            JsonNode items = order.path("orderItemList");
            if (items.isArray() && !items.isEmpty()) {
                JsonNode first = items.get(0);
                requestBody.put("productId", first.path("productId").asLong());
                requestBody.put("productName", first.path("productName").asText(""));
                requestBody.put("productPic", first.path("productPic").asText(""));
                requestBody.put("productCount", first.path("productQuantity").asInt(1));
                requestBody.put("productPrice", first.path("productPrice").asDouble(0));
                requestBody.put("productRealPrice", first.path("productPrice").asDouble(0));
                requestBody.put("productBrand", first.path("productBrand").asText(""));
                requestBody.put("productAttr", first.path("productAttr").asText(""));
            }

            String url = mallPortalUrl + "/returnApply/create";
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, buildHeaders(context));
            String response = restTemplate.postForObject(url, entity, String.class);
            log.info("Create after sale completed for memberId={}, orderSn={}", context.getMemberId(), orderSn);
            return response;
        } catch (Exception e) {
            log.error("创建售后工单失败: {}", e.getMessage());
            return "{\"error\": \"创建售后工单失败\"}";
        }
    }

    /**
     * 按订单编号解析订单详情，并完成归属校验。
     * <p>
     * mall-portal 的订单详情接口按数字主键查询，而用户在对话中提供的是订单编号（orderSn），
     * 因此先通过 /order/list 定位订单（服务端本身按登录会员过滤），拿到主键后再查详情；
     * 纯数字输入直接按主键兜底查询。详情返回后本地再次校验归属，越权或找不到均返回 null。
     */
    private JsonNode resolveOrder(String orderSn, ToolInvocationContext context) {
        try {
            Long orderId = null;

            String listUrl = mallPortalUrl + "/order/list?status=-1&pageNum=1&pageSize=50";
            String listResponse = getWithAuth(listUrl, new HashMap<>(), context);
            JsonNode list = objectMapper.readTree(listResponse).path("data").path("list");
            if (list.isArray()) {
                for (JsonNode item : list) {
                    if (orderSn.equalsIgnoreCase(item.path("orderSn").asText(""))) {
                        orderId = item.path("id").asLong(0);
                        break;
                    }
                }
            }

            if (orderId == null && orderSn.matches("\\d+")) {
                orderId = Long.parseLong(orderSn);
            }
            if (orderId == null || orderId <= 0) {
                return null;
            }

            String detailUrl = mallPortalUrl + "/order/detail/{orderId}";
            Map<String, Object> uriVariables = new HashMap<>();
            uriVariables.put("orderId", orderId);
            String detailResponse = getWithAuth(detailUrl, uriVariables, context);
            JsonNode data = objectMapper.readTree(detailResponse).path("data");
            if (data.isMissingNode() || data.isNull()) {
                return null;
            }

            // 纵深防御：detail 接口本身不校验归属，本地必须校验，防止后端漏鉴权造成越权
            String owner = data.path("memberId").isMissingNode() ? null : data.path("memberId").asText();
            if (owner == null || !owner.equals(context.getMemberId())) {
                log.warn("订单归属校验失败，疑似越权访问, orderSn={}, memberId={}", orderSn, context.getMemberId());
                return null;
            }
            return data;
        } catch (Exception e) {
            log.error("解析订单失败: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 公共方法 ====================

    /**
     * 带鉴权头的 GET 请求
     */
    private String getWithAuth(String url, Map<String, Object> uriVariables, ToolInvocationContext context) {
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(context));
        ResponseEntity<String> response =
                restTemplate.exchange(url, HttpMethod.GET, entity, String.class, uriVariables);
        return response.getBody();
    }

    /**
     * 带鉴权头的 POST 请求（JSON 体）
     */
    private String postWithAuth(String url, Object body, ToolInvocationContext context) {
        HttpEntity<Object> entity = new HttpEntity<>(body, buildHeaders(context));
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
        return response.getBody();
    }

    /**
     * 构造请求头：内部服务令牌 + 用户 JWT（均为可选，取不到就不发送）
     */
    private HttpHeaders buildHeaders(ToolInvocationContext context) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        if (internalServiceToken != null && !internalServiceToken.isBlank()) {
            headers.set(INTERNAL_TOKEN_HEADER, internalServiceToken);
        }
        if (context != null && context.getUserToken() != null && !context.getUserToken().isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + context.getUserToken());
        }
        return headers;
    }
}
