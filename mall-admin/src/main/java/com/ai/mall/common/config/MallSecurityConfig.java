package com.ai.mall.config;

import com.ai.mall.model.UmsResource;
import com.ai.mall.security.component.DynamicSecurityService;
import com.ai.mall.service.UmsAdminService;
import com.ai.mall.service.UmsResourceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.ConfigAttribute;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * mall-security模块相关配置
 * Created by macro on 2019/11/9.
 */
@Slf4j
@Configuration
public class MallSecurityConfig {

    @Autowired
    private UmsAdminService adminService;
    @Autowired
    private UmsResourceService resourceService;

    @Bean
    public UserDetailsService userDetailsService() {
        //获取登录用户信息
        return username -> adminService.loadUserByUsername(username);
    }

    @Bean
    public DynamicSecurityService dynamicSecurityService() {
        return new DynamicSecurityService() {
            @Override
            public Map<String, ConfigAttribute> loadDataSource() {
                Map<String, ConfigAttribute> map = new ConcurrentHashMap<>();
                try {
                    List<UmsResource> resourceList = resourceService.listAll();
                    for (UmsResource resource : resourceList) {
                        if (resource.getUrl() == null || resource.getUrl().isBlank()) {
                            continue;
                        }
                        map.put(resource.getUrl(),
                                new org.springframework.security.access.SecurityConfig(
                                        resource.getId() + ":" + resource.getName()));
                    }
                } catch (Exception e) {
                    // 容错：ums_resource 等权限表尚未初始化（如首次启动、init.sql 未建全表）时，
                    // 不能因为启动阶段的权限加载直接导致应用崩溃。
                    // 降级为空规则：管理接口退化为依赖 secure.ignored.urls 白名单，登录/健康检查仍可用；
                    // 待表初始化完成后可通过管理端重新加载或重启恢复完整动态鉴权。
                    log.error("加载动态权限资源失败，请确认 ums_resource 表已初始化: {}", e.getMessage());
                }
                return map;
            }
        };
    }
}
