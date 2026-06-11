package com.ai.mall.search.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis相关配置
 * Created by macro on 2019/4/8.
 */
@Configuration
@MapperScan({"com.ai.mall.mapper","com.ai.mall.search.dao"})
public class MyBatisConfig {
}
