package com.ai.mall.agent.test.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 环境可达性探针结论。
 *
 * <p>解决的问题：被测服务没启动时，报告会给出满屏红灯。使用者必须先自己判断
 * "是环境挂了还是代码坏了"——这个判断本该由工具给出，而不是靠人肉区分连接失败与真实缺陷。
 * 报告头部显式带上本对象，红灯有了明确的归因。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnvironmentCheck {

    /** 被测目标（模块对应的 base URL） */
    private String target;

    private String module;

    /** 是否可达（探针被关闭时为 false 且 skipped=true） */
    private boolean reachable;

    /** 探针是否被跳过（配置关闭） */
    private boolean skipped;

    /** 探针响应状态码；连接失败为 null */
    private Integer statusCode;

    /** 探测耗时（毫秒） */
    private long latencyMs;

    /** 结论说明：UP / 连接失败原因 / 健康状态异常值 */
    private String detail;
}
