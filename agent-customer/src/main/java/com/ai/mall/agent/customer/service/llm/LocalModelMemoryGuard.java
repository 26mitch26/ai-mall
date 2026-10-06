package com.ai.mall.agent.customer.service.llm;

/** Conservative disk-size plus overhead estimate; not a measured model residency requirement. */
public final class LocalModelMemoryGuard {
    private LocalModelMemoryGuard() {}
    public static boolean permits(Long modelBytes, long total, long free) {
        return modelBytes==null || modelBytes<=0 || total<=0 || modelBytes <= free-total/10-512L*1024*1024;
    }
    public static void check(Long modelBytes) {
        var bean=java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        if(bean instanceof com.sun.management.OperatingSystemMXBean memory
                && !permits(modelBytes,memory.getTotalMemorySize(),memory.getFreeMemorySize()))
            throw new IllegalStateException("当前可用内存不足，建议选择小模型或先释放内存；本次未加载模型");
    }
}
