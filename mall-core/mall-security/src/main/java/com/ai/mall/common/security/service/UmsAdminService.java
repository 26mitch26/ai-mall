package com.ai.mall.common.security.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

/**
 * Spring Security用户认证服务
 * 实现UserDetailsService接口，提供统一的用户认证逻辑，复用于所有业务模块
 * 子类需实现loadUserDetails和getResourceList方法以对接具体业务数据源
 * Created by macro on 2018/4/26.
 */
public abstract class UmsAdminService implements UserDetailsService {

    private static final Logger LOGGER = LoggerFactory.getLogger(UmsAdminService.class);

    @Autowired
    private PasswordEncoder passwordEncoder;

    /**
     * 根据用户名加载用户详情，由具体业务模块实现
     *
     * @param username 用户名
     * @return 用户详情对象，未找到时返回null
     */
    protected abstract UserDetails loadUserDetails(String username);

    /**
     * 根据用户ID获取资源权限列表，由具体业务模块实现
     *
     * @param userId 用户ID
     * @return 资源权限标识列表
     */
    protected abstract List<String> getResourceList(Long userId);

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        UserDetails userDetails = loadUserDetails(username);
        if (userDetails == null) {
            LOGGER.warn("用户不存在:{}", username);
            throw new UsernameNotFoundException("用户名或密码错误");
        }
        return userDetails;
    }

    /**
     * 用户密码校验
     *
     * @param rawPassword      原始密码
     * @param encodedPassword 加密后的密码
     * @return 密码是否匹配
     */
    public boolean authenticate(String rawPassword, String encodedPassword) {
        return passwordEncoder.matches(rawPassword, encodedPassword);
    }
}
