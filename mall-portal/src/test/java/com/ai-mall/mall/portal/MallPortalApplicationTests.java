package com.ai.mall.portal;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** 需要完整上下文与 MySQL 的冒烟测试，默认构建不执行 */
@SpringBootTest
@Tag("integration")
public class MallPortalApplicationTests {

    @Test
    public void contextLoads() {
    }

}
