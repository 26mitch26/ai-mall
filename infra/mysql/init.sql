-- AI-Mall 数据库初始化脚本（v3：本地演示闭环 + 自愈重复执行）
--
-- 作用：
--  1) 全新环境：由 scripts/init-local-db.ps1 导入本机 MySQL；
--  2) 存量环境：可直接重复执行（CREATE TABLE IF NOT EXISTS + DELETE+INSERT），
--     用于修复"表结构缺失 / 中文乱码 / 数据不足"等历史问题。
--
-- 中文乱码说明：init.sql 为 UTF-8 文件，执行时务必让 mysql 客户端按 utf8mb4 连接，
--  如：mysql -uroot -p --default-character-set=utf8mb4 < init.sql
--  （否则 UTF-8 字节会被误按 latin1 解读，出现 '测试手机' -> 'æµ‹è¯•æ‰‹æœº' 双重乱码）

CREATE DATABASE IF NOT EXISTS `mall` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE `mall`;

-- ============================================================================
-- 一、用户/订单域（精简）
-- ============================================================================

CREATE TABLE IF NOT EXISTS `ums_member` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_level_id` bigint DEFAULT NULL,
  `username` varchar(64) DEFAULT NULL COMMENT '用户名',
  `password` varchar(100) DEFAULT NULL COMMENT 'BCrypt 密码',
  `nickname` varchar(64) DEFAULT NULL COMMENT '昵称',
  `phone` varchar(64) DEFAULT NULL COMMENT '手机号',
  `email` varchar(64) DEFAULT NULL COMMENT '邮箱',
  `icon` varchar(500) DEFAULT NULL COMMENT '头像',
  `gender` int DEFAULT NULL COMMENT '性别',
  `birthday` date DEFAULT NULL COMMENT '生日',
  `city` varchar(64) DEFAULT NULL COMMENT '所在城市',
  `job` varchar(100) DEFAULT NULL COMMENT '职业',
  `personalized_signature` varchar(200) DEFAULT NULL COMMENT '个性签名',
  `source_type` int DEFAULT NULL COMMENT '用户来源',
  `integration` int DEFAULT NULL COMMENT '积分',
  `growth` int DEFAULT NULL COMMENT '成长值',
  `luckey_count` int DEFAULT 0 COMMENT '剩余抽奖次数',
  `history_integration` int DEFAULT 0 COMMENT '历史积分',
  `status` int DEFAULT NULL COMMENT '启用状态',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_username` (`username`),
  UNIQUE KEY `idx_phone` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员表';

-- 兼容已存在的旧版 ums_member：只补列，不删除用户数据。
DROP PROCEDURE IF EXISTS `ai_mall_add_column_if_missing`;
DELIMITER $$
CREATE PROCEDURE `ai_mall_add_column_if_missing`(
  IN table_name_value varchar(64),
  IN column_name_value varchar(64),
  IN column_definition_value varchar(500)
)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = table_name_value
      AND COLUMN_NAME = column_name_value
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', table_name_value, '` ADD COLUMN `',
                      column_name_value, '` ', column_definition_value);
    PREPARE ddl_statement FROM @ddl;
    EXECUTE ddl_statement;
    DEALLOCATE PREPARE ddl_statement;
  END IF;
END$$
DELIMITER ;

CALL `ai_mall_add_column_if_missing`('ums_member', 'nickname', 'varchar(64) DEFAULT NULL COMMENT ''昵称''');
CALL `ai_mall_add_column_if_missing`('ums_member', 'birthday', 'date DEFAULT NULL COMMENT ''生日''');
CALL `ai_mall_add_column_if_missing`('ums_member', 'personalized_signature', 'varchar(200) DEFAULT NULL COMMENT ''个性签名''');
CALL `ai_mall_add_column_if_missing`('ums_member', 'luckey_count', 'int DEFAULT 0 COMMENT ''剩余抽奖次数''');
CALL `ai_mall_add_column_if_missing`('ums_member', 'history_integration', 'int DEFAULT 0 COMMENT ''历史积分''');
ALTER TABLE `ums_member` MODIFY COLUMN `password` varchar(100) DEFAULT NULL COMMENT 'BCrypt 密码';
DROP PROCEDURE `ai_mall_add_column_if_missing`;

CREATE TABLE IF NOT EXISTS `ums_member_level` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) DEFAULT NULL,
  `growth_point` int DEFAULT 0,
  `default_status` int DEFAULT 0,
  `free_freight_point` decimal(10,2) DEFAULT 0,
  `comment_growth_point` int DEFAULT 0,
  `priviledge_free_freight` int DEFAULT 0,
  `priviledge_sign_in` int DEFAULT 0,
  `priviledge_comment` int DEFAULT 0,
  `priviledge_promotion` int DEFAULT 0,
  `priviledge_member_price` int DEFAULT 0,
  `priviledge_birthday` int DEFAULT 0,
  `note` varchar(200) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员等级';

CREATE TABLE IF NOT EXISTS `ums_member_receive_address` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint DEFAULT NULL,
  `name` varchar(100) DEFAULT NULL,
  `phone_number` varchar(64) DEFAULT NULL,
  `default_status` int DEFAULT 0,
  `post_code` varchar(32) DEFAULT NULL,
  `province` varchar(64) DEFAULT NULL,
  `city` varchar(64) DEFAULT NULL,
  `region` varchar(64) DEFAULT NULL,
  `detail_address` varchar(200) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_member_id` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员收货地址';

-- 后台账号、菜单与动态权限表
CREATE TABLE IF NOT EXISTS `ums_admin` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(64) NOT NULL,
  `password` varchar(100) NOT NULL,
  `icon` varchar(500) DEFAULT NULL,
  `email` varchar(100) DEFAULT NULL,
  `nick_name` varchar(64) DEFAULT NULL,
  `note` varchar(500) DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `login_time` datetime DEFAULT NULL,
  `status` int DEFAULT 1,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='后台管理员';

CREATE TABLE IF NOT EXISTS `ums_admin_login_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `admin_id` bigint DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `ip` varchar(64) DEFAULT NULL,
  `address` varchar(100) DEFAULT NULL,
  `user_agent` varchar(500) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_admin_id` (`admin_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='后台登录日志';

CREATE TABLE IF NOT EXISTS `ums_role` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL,
  `description` varchar(500) DEFAULT NULL,
  `admin_count` int DEFAULT 0,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `status` int DEFAULT 1,
  `sort` int DEFAULT 0,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='后台角色';

CREATE TABLE IF NOT EXISTS `ums_admin_role_relation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `admin_id` bigint NOT NULL,
  `role_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_admin_role` (`admin_id`,`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `ums_menu` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `parent_id` bigint DEFAULT 0,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `title` varchar(100) NOT NULL,
  `level` int DEFAULT 0,
  `sort` int DEFAULT 0,
  `name` varchar(100) DEFAULT NULL,
  `icon` varchar(100) DEFAULT NULL,
  `hidden` int DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_menu_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='后台菜单';

CREATE TABLE IF NOT EXISTS `ums_role_menu_relation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `role_id` bigint NOT NULL,
  `menu_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_menu` (`role_id`,`menu_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `ums_resource_category` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `name` varchar(100) NOT NULL,
  `sort` int DEFAULT 0,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `ums_resource` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `name` varchar(100) NOT NULL,
  `url` varchar(200) DEFAULT NULL,
  `description` varchar(500) DEFAULT NULL,
  `category_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_resource_url` (`url`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `ums_role_resource_relation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `role_id` bigint NOT NULL,
  `resource_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_resource` (`role_id`,`resource_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `oms_cart_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `product_id` bigint DEFAULT NULL,
  `product_sku_id` bigint DEFAULT NULL,
  `member_id` bigint DEFAULT NULL,
  `quantity` int DEFAULT 1,
  `price` decimal(10,2) DEFAULT NULL,
  `product_pic` varchar(500) DEFAULT NULL,
  `product_name` varchar(200) DEFAULT NULL,
  `product_sub_title` varchar(255) DEFAULT NULL,
  `product_sku_code` varchar(64) DEFAULT NULL,
  `member_nickname` varchar(64) DEFAULT NULL,
  `create_date` datetime DEFAULT CURRENT_TIMESTAMP,
  `modify_date` datetime DEFAULT CURRENT_TIMESTAMP,
  `delete_status` int DEFAULT 0,
  `product_category_id` bigint DEFAULT NULL,
  `product_brand` varchar(100) DEFAULT NULL,
  `product_sn` varchar(100) DEFAULT NULL,
  `product_attr` varchar(500) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_cart_member` (`member_id`,`delete_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='购物车';

CREATE TABLE IF NOT EXISTS `oms_order_setting` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `flash_order_overtime` int DEFAULT 60,
  `normal_order_overtime` int DEFAULT 120,
  `confirm_overtime` int DEFAULT 15,
  `finish_overtime` int DEFAULT 7,
  `comment_overtime` int DEFAULT 7,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单设置';

CREATE TABLE IF NOT EXISTS `ums_integration_consume_setting` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `deduction_per_amount` int DEFAULT 100,
  `max_percent_per_order` int DEFAULT 50,
  `use_unit` int DEFAULT 100,
  `coupon_status` int DEFAULT 1,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='积分消费设置';

CREATE TABLE IF NOT EXISTS `sms_coupon_history` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `coupon_id` bigint DEFAULT NULL,
  `member_id` bigint DEFAULT NULL,
  `coupon_code` varchar(64) DEFAULT NULL,
  `member_nickname` varchar(64) DEFAULT NULL,
  `get_type` int DEFAULT 0,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `use_status` int DEFAULT 0,
  `use_time` datetime DEFAULT NULL,
  `order_id` bigint DEFAULT NULL,
  `order_sn` varchar(64) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_coupon_member` (`coupon_id`,`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='优惠券领取记录';

CREATE TABLE IF NOT EXISTS `oms_order` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint DEFAULT NULL COMMENT '会员id',
  `order_sn` varchar(64) DEFAULT NULL COMMENT '订单编号',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `member_username` varchar(64) DEFAULT NULL COMMENT '会员用户名',
  `total_amount` decimal(10,2) DEFAULT NULL COMMENT '订单总金额',
  `pay_amount` decimal(10,2) DEFAULT NULL COMMENT '应付金额',
  `freight_amount` decimal(10,2) DEFAULT NULL COMMENT '运费',
  `status` int DEFAULT NULL COMMENT '订单状态',
  `pay_type` int DEFAULT NULL COMMENT '支付方式',
  `delivery_company` varchar(64) DEFAULT NULL COMMENT '物流公司',
  `delivery_sn` varchar(64) DEFAULT NULL COMMENT '物流单号',
  `receiver_name` varchar(100) DEFAULT NULL COMMENT '收货人',
  `receiver_phone` varchar(100) DEFAULT NULL COMMENT '收货人电话',
  `receiver_post_code` varchar(100) DEFAULT NULL COMMENT '收货人邮编',
  `receiver_province` varchar(100) DEFAULT NULL COMMENT '省份',
  `receiver_city` varchar(100) DEFAULT NULL COMMENT '城市',
  `receiver_region` varchar(100) DEFAULT NULL COMMENT '区',
  `receiver_detail_address` varchar(200) DEFAULT NULL COMMENT '详细地址',
  `note` varchar(500) DEFAULT NULL COMMENT '备注',
  `auto_confirm_day` int DEFAULT NULL COMMENT '自动确认天数',
  `integration` int DEFAULT NULL COMMENT '可以获得的积分',
  `growth` int DEFAULT NULL COMMENT '可以获得的成长值',
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_order_sn` (`order_sn`),
  KEY `idx_member_id` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表';

CREATE TABLE IF NOT EXISTS `oms_order_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint DEFAULT NULL COMMENT '订单id',
  `order_sn` varchar(64) DEFAULT NULL COMMENT '订单编号',
  `product_id` bigint DEFAULT NULL COMMENT '商品id',
  `product_name` varchar(200) DEFAULT NULL COMMENT '商品名称',
  `product_pic` varchar(500) DEFAULT NULL COMMENT '商品图片',
  `product_price` decimal(10,2) DEFAULT NULL COMMENT '商品价格',
  `product_quantity` int DEFAULT NULL COMMENT '商品数量',
  `product_sku_id` bigint DEFAULT NULL COMMENT '商品sku编号',
  `product_sn` varchar(200) DEFAULT NULL COMMENT '商品货号',
  `product_sku_code` varchar(200) DEFAULT NULL COMMENT '商品sku条码',
  `product_category_id` bigint DEFAULT NULL COMMENT '商品分类id',
  `promotion_name` varchar(200) DEFAULT NULL COMMENT '促销名称',
  `promotion_amount` decimal(10,2) DEFAULT NULL COMMENT '促销分摊金额',
  `coupon_amount` decimal(10,2) DEFAULT NULL COMMENT '优惠券分摊金额',
  `integration_amount` decimal(10,2) DEFAULT NULL COMMENT '积分分摊金额',
  `real_amount` decimal(10,2) DEFAULT NULL COMMENT '商品实际支付单价',
  `gift_integration` int DEFAULT NULL COMMENT '赠送的积分',
  `gift_growth` int DEFAULT NULL COMMENT '赠送的成长值',
  PRIMARY KEY (`id`),
  KEY `idx_order_id` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单商品信息表';

CREATE TABLE IF NOT EXISTS `oms_order_operate_history` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint DEFAULT NULL COMMENT '订单id',
  `operate_man` varchar(100) DEFAULT NULL COMMENT '操作人',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
  `order_status` int DEFAULT NULL COMMENT '订单状态',
  `note` varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`id`),
  KEY `idx_history_order_id` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单操作历史';

-- 兼容早期精简订单表：后台订单列表/详情依赖这些字段。
DROP PROCEDURE IF EXISTS `ai_mall_add_order_column_if_missing`;
DELIMITER $$
CREATE PROCEDURE `ai_mall_add_order_column_if_missing`(
  IN table_name_value varchar(64),
  IN column_name_value varchar(64),
  IN column_definition_value varchar(500)
)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = table_name_value
      AND COLUMN_NAME = column_name_value
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', table_name_value, '` ADD COLUMN `',
                      column_name_value, '` ', column_definition_value);
    PREPARE ddl_statement FROM @ddl;
    EXECUTE ddl_statement;
    DEALLOCATE PREPARE ddl_statement;
  END IF;
END$$
DELIMITER ;

CALL `ai_mall_add_order_column_if_missing`('oms_order', 'coupon_id', 'bigint DEFAULT NULL');
CALL `ai_mall_add_order_column_if_missing`('oms_order', 'promotion_amount', 'decimal(10,2) DEFAULT NULL');
CALL `ai_mall_add_order_column_if_missing`('oms_order', 'integration_amount', 'decimal(10,2) DEFAULT NULL');
CALL `ai_mall_add_order_column_if_missing`('oms_order', 'coupon_amount', 'decimal(10,2) DEFAULT NULL');
CALL `ai_mall_add_order_column_if_missing`('oms_order', 'discount_amount', 'decimal(10,2) DEFAULT NULL');
CALL `ai_mall_add_order_column_if_missing`('oms_order', 'source_type', 'int DEFAULT 0');
CALL `ai_mall_add_order_column_if_missing`('oms_order', 'order_type', 'int DEFAULT 0');
CALL `ai_mall_add_order_column_if_missing`('oms_order', 'promotion_info', 'varchar(255) DEFAULT NULL');
CALL `ai_mall_add_order_column_if_missing`('oms_order', 'bill_type', 'int DEFAULT 0');
CALL `ai_mall_add_order_column_if_missing`('oms_order', 'bill_header', 'varchar(200) DEFAULT NULL');
CALL `ai_mall_add_order_column_if_missing`('oms_order', 'bill_content', 'varchar(200) DEFAULT NULL');
CALL `ai_mall_add_order_column_if_missing`('oms_order', 'bill_receiver_phone', 'varchar(64) DEFAULT NULL');
CALL `ai_mall_add_order_column_if_missing`('oms_order', 'bill_receiver_email', 'varchar(100) DEFAULT NULL');
CALL `ai_mall_add_order_column_if_missing`('oms_order', 'confirm_status', 'int DEFAULT 0');
CALL `ai_mall_add_order_column_if_missing`('oms_order', 'delete_status', 'int DEFAULT 0');
CALL `ai_mall_add_order_column_if_missing`('oms_order', 'use_integration', 'int DEFAULT 0');
CALL `ai_mall_add_order_column_if_missing`('oms_order', 'payment_time', 'datetime DEFAULT NULL');
CALL `ai_mall_add_order_column_if_missing`('oms_order', 'delivery_time', 'datetime DEFAULT NULL');
CALL `ai_mall_add_order_column_if_missing`('oms_order', 'receive_time', 'datetime DEFAULT NULL');
CALL `ai_mall_add_order_column_if_missing`('oms_order', 'comment_time', 'datetime DEFAULT NULL');
CALL `ai_mall_add_order_column_if_missing`('oms_order', 'modify_time', 'datetime DEFAULT NULL');
CALL `ai_mall_add_order_column_if_missing`('oms_order_item', 'product_brand', 'varchar(200) DEFAULT NULL');
CALL `ai_mall_add_order_column_if_missing`('oms_order_item', 'product_attr', 'varchar(500) DEFAULT NULL');
-- 订单明细表补齐 MyBatis 模型字段：缺失时 /order/list、/order/detail 会 500（Unknown column 'product_sku_code'）
CALL `ai_mall_add_order_column_if_missing`('oms_order_item', 'product_sku_code', 'varchar(200) DEFAULT NULL');
CALL `ai_mall_add_order_column_if_missing`('oms_order_item', 'product_category_id', 'bigint DEFAULT NULL');
CALL `ai_mall_add_order_column_if_missing`('oms_order_item', 'promotion_name', 'varchar(200) DEFAULT NULL');
CALL `ai_mall_add_order_column_if_missing`('oms_order_item', 'promotion_amount', 'decimal(10,2) DEFAULT NULL');
CALL `ai_mall_add_order_column_if_missing`('oms_order_item', 'coupon_amount', 'decimal(10,2) DEFAULT NULL');
CALL `ai_mall_add_order_column_if_missing`('oms_order_item', 'integration_amount', 'decimal(10,2) DEFAULT NULL');
CALL `ai_mall_add_order_column_if_missing`('oms_order_item', 'real_amount', 'decimal(10,2) DEFAULT NULL');
CALL `ai_mall_add_order_column_if_missing`('oms_order_item', 'gift_integration', 'int DEFAULT NULL');
CALL `ai_mall_add_order_column_if_missing`('oms_order_item', 'gift_growth', 'int DEFAULT NULL');
DROP PROCEDURE `ai_mall_add_order_column_if_missing`;

CREATE TABLE IF NOT EXISTS `pms_product_attribute_category` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(64) DEFAULT NULL COMMENT '属性分类名称',
  `attribute_count` int DEFAULT 0 COMMENT '属性数量',
  `param_count` int DEFAULT 0 COMMENT '参数数量',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产品属性分类';

-- 产品的分类和属性的关系表（设置分类筛选条件时使用）。
-- 此前存量库与 init.sql 均缺此表，管理端"商品属性 attrInfo"接口报
-- Table 'mall.pms_product_category_attribute_relation' doesn't exist（实测 500）。
CREATE TABLE IF NOT EXISTS `pms_product_category_attribute_relation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `product_category_id` bigint DEFAULT NULL COMMENT '商品分类ID',
  `product_attribute_id` bigint DEFAULT NULL COMMENT '商品属性ID',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产品的分类和属性的关系表';

CREATE TABLE IF NOT EXISTS `oms_order_return_reason` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) DEFAULT NULL COMMENT '退货原因',
  `sort` int DEFAULT 0 COMMENT '排序',
  `status` int DEFAULT 1 COMMENT '启用状态',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='退货原因';

CREATE TABLE IF NOT EXISTS `oms_order_return_apply` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint DEFAULT NULL,
  `company_address_id` bigint DEFAULT NULL,
  `product_id` bigint DEFAULT NULL,
  `order_sn` varchar(64) DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `member_username` varchar(64) DEFAULT NULL,
  `return_amount` decimal(10,2) DEFAULT NULL,
  `return_name` varchar(100) DEFAULT NULL,
  `return_phone` varchar(64) DEFAULT NULL,
  `status` int DEFAULT 0,
  `handle_time` datetime DEFAULT NULL,
  `product_pic` varchar(500) DEFAULT NULL,
  `product_name` varchar(200) DEFAULT NULL,
  `product_brand` varchar(200) DEFAULT NULL,
  `product_attr` varchar(500) DEFAULT NULL,
  `product_count` int DEFAULT 1,
  `product_price` decimal(10,2) DEFAULT NULL,
  `product_real_price` decimal(10,2) DEFAULT NULL,
  `reason` varchar(200) DEFAULT NULL,
  `description` varchar(500) DEFAULT NULL,
  `proof_pics` varchar(1000) DEFAULT NULL,
  `handle_note` varchar(500) DEFAULT NULL,
  `handle_man` varchar(100) DEFAULT NULL,
  `receive_man` varchar(100) DEFAULT NULL,
  `receive_time` datetime DEFAULT NULL,
  `receive_note` varchar(500) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_return_apply_status` (`status`),
  KEY `idx_return_apply_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='退货申请';

CREATE TABLE IF NOT EXISTS `oms_company_address` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `address_name` varchar(200) DEFAULT NULL,
  `send_status` int DEFAULT 0,
  `receive_status` int DEFAULT 0,
  `name` varchar(100) DEFAULT NULL,
  `phone` varchar(64) DEFAULT NULL,
  `province` varchar(100) DEFAULT NULL,
  `city` varchar(100) DEFAULT NULL,
  `region` varchar(100) DEFAULT NULL,
  `detail_address` varchar(200) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='公司收发货地址';

CREATE TABLE IF NOT EXISTS `cms_prefrence_area` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(255) DEFAULT NULL,
  `sub_title` varchar(255) DEFAULT NULL,
  `pic` varchar(500) DEFAULT NULL,
  `sort` int DEFAULT 0,
  `show_status` int DEFAULT 1,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品优选专区';

-- ============================================================================
-- 二、商品域（portal 搜索/详情/ES 导入所依赖的完整表集合）
-- ============================================================================

CREATE TABLE IF NOT EXISTS `pms_product_category` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `parent_id` bigint DEFAULT 0 COMMENT '父分类的编号',
  `name` varchar(64) DEFAULT NULL COMMENT '分类名称',
  `level` int DEFAULT NULL COMMENT '分类级别',
  `product_unit` varchar(64) DEFAULT NULL COMMENT '商品单位',
  `icon` varchar(255) DEFAULT NULL COMMENT '图标',
  `sort` int DEFAULT NULL COMMENT '排序',
  `show_status` int DEFAULT NULL COMMENT '显示状态',
  `nav_status` int DEFAULT 0 COMMENT '是否在导航栏显示',
  `product_count` int DEFAULT NULL COMMENT '商品数量',
  `product_attribute_count` int DEFAULT NULL COMMENT '商品属性数量',
  `keywords` varchar(255) DEFAULT NULL COMMENT '分类关键词',
  `description` text COMMENT '分类描述',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品分类表';

CREATE TABLE IF NOT EXISTS `pms_brand` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(64) DEFAULT NULL COMMENT '品牌名称',
  `first_letter` varchar(8) DEFAULT NULL COMMENT '首字母',
  `sort` int DEFAULT NULL COMMENT '排序',
  `factory_status` int DEFAULT NULL COMMENT '是否为品牌制造商：0->不是；1->是',
  `show_status` int DEFAULT NULL COMMENT '是否显示：0->不显示；1->显示',
  `product_count` int DEFAULT NULL COMMENT '产品数量',
  `product_comment_count` int DEFAULT NULL COMMENT '产品评论数量',
  `logo` varchar(255) DEFAULT NULL COMMENT '品牌logo',
  `big_pic` varchar(255) DEFAULT NULL COMMENT '专区大图',
  `brand_story` longtext COMMENT '品牌故事', -- 修复记录（§九）：漏列导致压测 500，见 PmsBrandMapper.xml 的 select/resultMap
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='品牌表';

-- 商品表：字段需与 mall-mbg 的 PmsProductMapper/实体完全一致（含 product_sn）
CREATE TABLE IF NOT EXISTS `pms_product` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `brand_id` bigint DEFAULT NULL COMMENT '品牌id',
  `product_category_id` bigint DEFAULT NULL COMMENT '商品分类id',
  `feight_template_id` bigint DEFAULT NULL COMMENT '运费模版id',
  `product_attribute_category_id` bigint DEFAULT NULL COMMENT '产品属性分类id',
  `name` varchar(64) DEFAULT NULL COMMENT '商品名称',
  `pic` varchar(255) DEFAULT NULL COMMENT '商品主图',
  `product_sn` varchar(64) DEFAULT NULL COMMENT '货号',
  `delete_status` int DEFAULT 0 COMMENT '删除状态：0->未删除；1->已删除',
  `publish_status` int DEFAULT 1 COMMENT '上架状态：0->下架；1->上架',
  `new_status` int DEFAULT 0 COMMENT '新品状态:0->不是新品；1->新品',
  `recommand_status` int DEFAULT 0 COMMENT '推荐状态；0->不推荐；1->推荐',
  `verify_status` int DEFAULT 0 COMMENT '审核状态：0->未审核；1->审核通过',
  `sort` int DEFAULT 0 COMMENT '排序',
  `sale` int DEFAULT 0 COMMENT '销量',
  `price` decimal(10,2) DEFAULT NULL COMMENT '价格',
  `promotion_price` decimal(10,2) DEFAULT NULL COMMENT '促销价格',
  `gift_growth` int DEFAULT 0 COMMENT '赠送的成长值',
  `gift_point` int DEFAULT 0 COMMENT '赠送的积分',
  `use_point_limit` int DEFAULT 0 COMMENT '限制使用的积分数',
  `sub_title` varchar(255) DEFAULT NULL COMMENT '副标题',
  `original_price` decimal(10,2) DEFAULT NULL COMMENT '市场价',
  `stock` int DEFAULT NULL COMMENT '库存',
  `low_stock` int DEFAULT 0 COMMENT '库存预警数量',
  `unit` varchar(16) DEFAULT NULL COMMENT '单位',
  `weight` decimal(10,2) DEFAULT NULL COMMENT '重量',
  `preview_status` int DEFAULT 0 COMMENT '是否为预告商品：0->不是；1->是',
  `service_ids` varchar(64) DEFAULT NULL COMMENT '以逗号分割的产品服务：1->无忧退货；2->快速退款；3->免费包邮',
  `keywords` varchar(255) DEFAULT NULL COMMENT '关键词',
  `note` varchar(255) DEFAULT NULL COMMENT '备注',
  `album_pics` varchar(1000) DEFAULT NULL COMMENT '画册图片',
  `detail_title` varchar(255) DEFAULT NULL COMMENT '详情标题',
  `detail_desc` varchar(500) DEFAULT NULL COMMENT '详情描述',
  `detail_html` text COMMENT '产品详情网页内容',
  `detail_mobile_html` text COMMENT '移动端网页详情',
  `promotion_start_time` datetime DEFAULT NULL COMMENT '促销开始时间',
  `promotion_end_time` datetime DEFAULT NULL COMMENT '促销结束时间',
  `promotion_per_limit` int DEFAULT 0 COMMENT '活动限购数量',
  `promotion_type` int DEFAULT 0 COMMENT '促销类型：0->没有促销使用原价;1->使用促销价；2->使用会员价；3->使用阶梯价格；4->使用满减价格；5->限时购',
  `brand_name` varchar(255) DEFAULT NULL COMMENT '品牌名称（冗余）',
  `product_category_name` varchar(255) DEFAULT NULL COMMENT '商品分类名称（冗余）',
  `description` text COMMENT '商品描述',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_brand_id` (`brand_id`),
  KEY `idx_category_id` (`product_category_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品信息表';

-- 存量库补列：早期 init.sql 缺 product_sn，这里做幂等补列
SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA='mall' AND TABLE_NAME='pms_product' AND COLUMN_NAME='product_sn');
SET @ddl = IF(@col_exists=0,
  'ALTER TABLE `pms_product` ADD COLUMN `product_sn` varchar(64) DEFAULT NULL COMMENT ''货号'' AFTER `pic`',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS `pms_sku_stock` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `product_id` bigint DEFAULT NULL COMMENT '商品id',
  `sku_code` varchar(64) DEFAULT NULL COMMENT 'sku编码',
  `price` decimal(10,2) DEFAULT NULL COMMENT '价格',
  `stock` int DEFAULT NULL COMMENT '库存',
  `low_stock` int DEFAULT NULL COMMENT '预警库存',
  `pic` varchar(255) DEFAULT NULL COMMENT '展示图片',
  `sale` int DEFAULT NULL COMMENT '销量',
  `promotion_price` decimal(10,2) DEFAULT NULL COMMENT '促销价格',
  `lock_stock` int DEFAULT 0 COMMENT '锁定库存',
  `sp_data` varchar(500) DEFAULT NULL COMMENT '商品销售属性，json格式',
  PRIMARY KEY (`id`),
  KEY `idx_product_id` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品SKU库存表';

CREATE TABLE IF NOT EXISTS `pms_product_attribute` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `product_attribute_category_id` bigint DEFAULT NULL COMMENT '商品属性分类id',
  `name` varchar(64) DEFAULT NULL COMMENT '属性名称',
  `select_type` int DEFAULT NULL COMMENT '属性选择类型：0->唯一；1->单选；2->多选',
  `input_type` int DEFAULT NULL COMMENT '属性录入方式：0->手工录入；1->从列表中选取',
  `input_list` varchar(255) DEFAULT NULL COMMENT '可选值列表，逗号分隔',
  `sort` int DEFAULT NULL COMMENT '排序',
  `filter_type` int DEFAULT NULL COMMENT '分类筛选样式：0->普通；1->颜色',
  `search_type` int DEFAULT NULL COMMENT '检索类型；0->不需要进行检索；1->关键字检索；2->范围检索',
  `related_status` int DEFAULT NULL COMMENT '相同属性产品是否关联；0->不关联；1->关联',
  `hand_add_status` int DEFAULT NULL COMMENT '是否支持手动新增；0->不支持；1->支持',
  `type` int DEFAULT NULL COMMENT '属性的类型；0->规格；1->参数',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品属性表';

CREATE TABLE IF NOT EXISTS `pms_product_attribute_value` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `product_id` bigint DEFAULT NULL COMMENT '商品id',
  `product_attribute_id` bigint DEFAULT NULL COMMENT '商品属性id',
  `value` varchar(64) DEFAULT NULL COMMENT '手动添加规格或参数的值，参数单值，规格有多个时以逗号隔开',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='存储产品参数信息的表';

CREATE TABLE IF NOT EXISTS `pms_product_ladder` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `product_id` bigint DEFAULT NULL COMMENT '商品id',
  `count` int DEFAULT NULL COMMENT '满足的商品数量',
  `discount` decimal(10,2) DEFAULT NULL COMMENT '折扣',
  `price` decimal(10,2) DEFAULT NULL COMMENT '折后价格',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产品阶梯价格表';

CREATE TABLE IF NOT EXISTS `pms_product_full_reduction` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `product_id` bigint DEFAULT NULL COMMENT '商品id',
  `full_price` decimal(10,2) DEFAULT NULL COMMENT '商品满足价格',
  `reduce_price` decimal(10,2) DEFAULT NULL COMMENT '商品减少价格',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产品满减表';

-- ============================================================================
-- 三、优惠券域（商品详情页可用券列表依赖）
-- ============================================================================

CREATE TABLE IF NOT EXISTS `sms_coupon` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `type` int DEFAULT NULL COMMENT '优惠卷类型；0->全场赠券；1->会员赠券；2->购物赠券；3->注册赠券',
  `name` varchar(100) DEFAULT NULL COMMENT '名称',
  `platform` int DEFAULT NULL COMMENT '使用平台：0->全平台；1->移动端；2->PC',
  `count` int DEFAULT NULL COMMENT '数量',
  `amount` decimal(10,2) DEFAULT NULL COMMENT '金额',
  `per_limit` int DEFAULT NULL COMMENT '每人限领张数',
  `min_point` decimal(10,2) DEFAULT NULL COMMENT '使用门槛；0表示无门槛',
  `start_time` datetime DEFAULT NULL COMMENT '开始时间',
  `end_time` datetime DEFAULT NULL COMMENT '结束时间',
  `use_type` int DEFAULT NULL COMMENT '使用类型：0->全场通用；1->指定分类；2->指定商品',
  `note` varchar(200) DEFAULT NULL COMMENT '备注',
  `publish_count` int DEFAULT 0 COMMENT '发行数量',
  `use_count` int DEFAULT 0 COMMENT '已使用数量',
  `receive_count` int DEFAULT 0 COMMENT '领取数量',
  `enable_time` datetime DEFAULT NULL COMMENT '可以领取的日期',
  `code` varchar(64) DEFAULT NULL COMMENT '优惠码',
  `member_level` int DEFAULT NULL COMMENT '可领取的会员类型：0->无限时',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='优惠券表';

CREATE TABLE IF NOT EXISTS `sms_coupon_product_category_relation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `coupon_id` bigint DEFAULT NULL COMMENT '优惠券id',
  `product_category_id` bigint DEFAULT NULL COMMENT '产品分类id',
  `product_category_name` varchar(200) DEFAULT NULL COMMENT '产品分类名称',
  `parent_category_name` varchar(200) DEFAULT NULL COMMENT '父分类名称',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='优惠券和产品分类关系表';

CREATE TABLE IF NOT EXISTS `sms_coupon_product_relation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `coupon_id` bigint DEFAULT NULL COMMENT '优惠券id',
  `product_id` bigint DEFAULT NULL COMMENT '产品id',
  `product_name` varchar(500) DEFAULT NULL COMMENT '商品名称',
  `product_sn` varchar(200) DEFAULT NULL COMMENT '商品货号',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='优惠券和产品关系表';

-- ============================================================================
-- 四、首页内容域（/home/content 依赖：广告/品牌/秒杀/新品/热销/专题）
-- ============================================================================

CREATE TABLE IF NOT EXISTS `sms_home_advertise` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) DEFAULT NULL,
  `type` int DEFAULT NULL COMMENT '轮播位置：0->PC首页轮播；1->app首页轮播',
  `pic` varchar(500) DEFAULT NULL,
  `start_time` datetime DEFAULT NULL,
  `end_time` datetime DEFAULT NULL,
  `status` int DEFAULT NULL COMMENT '上下线状态：0->下线；1->上线',
  `click_count` int DEFAULT 0 COMMENT '点击数',
  `order_count` int DEFAULT 0 COMMENT '下单数',
  `url` varchar(500) DEFAULT NULL COMMENT '链接地址',
  `note` varchar(500) DEFAULT NULL COMMENT '备注',
  `sort` int DEFAULT 0 COMMENT '排序',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='首页轮播广告表';

CREATE TABLE IF NOT EXISTS `sms_home_brand` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `brand_id` bigint DEFAULT NULL COMMENT '品牌id',
  `brand_name` varchar(64) DEFAULT NULL COMMENT '品牌名称',
  `recommend_status` int DEFAULT NULL COMMENT '推荐状态：0->不推荐；1->推荐',
  `sort` int DEFAULT NULL COMMENT '排序',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='首页推荐品牌表';

CREATE TABLE IF NOT EXISTS `sms_home_new_product` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `product_id` bigint DEFAULT NULL COMMENT '商品id',
  `product_name` varchar(64) DEFAULT NULL COMMENT '商品名称',
  `recommend_status` int DEFAULT NULL COMMENT '推荐状态：0->不推荐；1->推荐',
  `sort` int DEFAULT NULL COMMENT '排序',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='新鲜好物表';

CREATE TABLE IF NOT EXISTS `sms_home_recommend_product` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `product_id` bigint DEFAULT NULL COMMENT '商品id',
  `product_name` varchar(64) DEFAULT NULL COMMENT '商品名称',
  `recommend_status` int DEFAULT NULL COMMENT '推荐状态：0->不推荐；1->推荐',
  `sort` int DEFAULT NULL COMMENT '排序',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='人气推荐商品表';

CREATE TABLE IF NOT EXISTS `sms_flash_promotion` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `title` varchar(200) DEFAULT NULL COMMENT '秒杀标题',
  `start_date` date DEFAULT NULL COMMENT '开始日期',
  `end_date` date DEFAULT NULL COMMENT '结束日期',
  `status` int DEFAULT NULL COMMENT '上下线状态',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='限时购表';

CREATE TABLE IF NOT EXISTS `sms_flash_promotion_session` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(200) DEFAULT NULL COMMENT '场次名称',
  `start_time` time DEFAULT NULL COMMENT '每日开始时间',
  `end_time` time DEFAULT NULL COMMENT '每日结束时间',
  `status` int DEFAULT NULL COMMENT '启用状态：0->不启用；1->启用',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='限时购场次表';

-- 旧版脚本误建为 datetime，会导致 MySQL 8/9 用纯时间条件查询时报 1525。
ALTER TABLE `sms_flash_promotion_session`
  MODIFY COLUMN `start_time` time DEFAULT NULL COMMENT '每日开始时间',
  MODIFY COLUMN `end_time` time DEFAULT NULL COMMENT '每日结束时间';

CREATE TABLE IF NOT EXISTS `sms_flash_promotion_product_relation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `flash_promotion_id` bigint DEFAULT NULL COMMENT '限时购id',
  `flash_promotion_session_id` bigint DEFAULT NULL COMMENT '限时购场次id',
  `product_id` bigint DEFAULT NULL COMMENT '商品id',
  `flash_promotion_price` decimal(10,2) DEFAULT NULL COMMENT '限时购价格',
  `flash_promotion_count` int DEFAULT NULL COMMENT '限时购数量',
  `flash_promotion_limit` int DEFAULT NULL COMMENT '每人限购数量',
  `sort` int DEFAULT NULL COMMENT '排序',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品限时购与商品关系表';

CREATE TABLE IF NOT EXISTS `cms_subject` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `category_id` bigint DEFAULT NULL COMMENT '专题分类id',
  `title` varchar(100) DEFAULT NULL COMMENT '专题名称',
  `pic` varchar(500) DEFAULT NULL COMMENT '专题主图',
  `product_count` int DEFAULT NULL COMMENT '关联产品数量',
  `recommend_status` int DEFAULT NULL COMMENT '推荐状态：0->不推荐；1->推荐',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `collect_count` int DEFAULT NULL COMMENT '收藏数',
  `read_count` int DEFAULT NULL COMMENT '阅读数',
  `comment_count` int DEFAULT NULL COMMENT '评论数',
  `album_pics` varchar(1000) DEFAULT NULL COMMENT '画册图片用逗号分割',
  `description` varchar(1000) DEFAULT NULL COMMENT '专题内容',
  `show_status` int DEFAULT NULL COMMENT '显示状态：0->不显示；1->显示',
  `forward_count` int DEFAULT NULL COMMENT '转发数',
  `category_name` varchar(100) DEFAULT NULL COMMENT '专题分类名称',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='专题表';

CREATE TABLE IF NOT EXISTS `sms_home_recommend_subject` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `subject_id` bigint DEFAULT NULL COMMENT '专题id',
  `subject_name` varchar(64) DEFAULT NULL COMMENT '专题名称',
  `recommend_status` int DEFAULT NULL COMMENT '推荐状态：0->不推荐；1->推荐',
  `sort` int DEFAULT NULL COMMENT '排序',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='首页推荐专题表';

-- ============================================================================
-- 五、种子数据（DELETE+INSERT 保证重复执行幂等，同时修复乱码/残缺数据）
-- ============================================================================

DELETE FROM `sms_home_recommend_subject`;
DELETE FROM `cms_subject`;
DELETE FROM `sms_flash_promotion_product_relation`;
DELETE FROM `sms_flash_promotion_session`;
DELETE FROM `sms_flash_promotion`;
DELETE FROM `sms_home_recommend_product`;
DELETE FROM `sms_home_new_product`;
DELETE FROM `sms_home_brand`;
DELETE FROM `sms_home_advertise`;
DELETE FROM `sms_coupon_product_relation`;
DELETE FROM `sms_coupon_product_category_relation`;
DELETE FROM `sms_coupon`;
DELETE FROM `pms_product_attribute_value`;
DELETE FROM `pms_product_attribute`;
DELETE FROM `pms_product_ladder`;
DELETE FROM `pms_product_full_reduction`;
DELETE FROM `pms_sku_stock`;
DELETE FROM `pms_product`;
DELETE FROM `pms_product_category`;
DELETE FROM `pms_brand`;
DELETE FROM `pms_product_attribute_category`;
DELETE FROM `oms_order_return_reason`;
DELETE FROM `oms_order_return_apply`;
DELETE FROM `oms_company_address`;
DELETE FROM `cms_prefrence_area`;

DELETE FROM `ums_member`;

INSERT INTO `pms_product_attribute_category`
(`id`,`name`,`attribute_count`,`param_count`) VALUES
(1,'手机基础参数',5,6),(2,'服装规格',4,4),(3,'食品规格',3,3),
(4,'电脑及数码',4,5),(5,'家电规格',3,4),(6,'运动户外',3,3)
AS new ON DUPLICATE KEY UPDATE
`name`=new.`name`,`attribute_count`=new.`attribute_count`,`param_count`=new.`param_count`;

INSERT INTO `oms_order_return_reason` (`id`,`name`,`sort`,`status`) VALUES
(1,'商品质量问题',1,1),(2,'商品与描述不符',2,1),(3,'不喜欢/不想要了',3,1),
(4,'发错商品',4,1),(5,'少件/漏发',5,1)
AS new ON DUPLICATE KEY UPDATE
`name`=new.`name`,`sort`=new.`sort`,`status`=new.`status`;

INSERT INTO `oms_order_return_apply`
(`id`,`order_id`,`product_id`,`order_sn`,`create_time`,`member_username`,`return_amount`,`return_name`,`return_phone`,`status`,`product_name`,`product_brand`,`product_count`,`product_price`,`product_real_price`,`reason`,`description`)
VALUES
(1,1,1,'AM-DEMO-20261001-001','2026-10-01 09:20:00','demo',6499.00,'演示用户','13800000001',0,'华为Mate60 Pro 旗舰手机5G','华为',1,6999.00,6499.00,'商品质量问题','演示退货申请：设备无法正常开机'),
(2,2,2,'AM-DEMO-20261001-002','2026-10-01 10:10:00','demo',3699.00,'演示用户','13800000001',1,'小米14 骁龙8Gen3 智能手机','小米',1,3999.00,3699.00,'商品与描述不符','演示退货申请：颜色与页面描述不一致')
AS new ON DUPLICATE KEY UPDATE
`order_sn`=new.`order_sn`,`status`=new.`status`,`reason`=new.`reason`,`description`=new.`description`;

INSERT INTO `oms_company_address`
(`id`,`address_name`,`send_status`,`receive_status`,`name`,`phone`,`province`,`city`,`region`,`detail_address`)
VALUES (1,'AI-Mall 本地演示仓',1,1,'AI-Mall 仓储中心','400-800-2026','上海市','上海市','浦东新区','张江高科技园区演示路 1 号')
AS new ON DUPLICATE KEY UPDATE
`address_name`=new.`address_name`,`send_status`=new.`send_status`,`receive_status`=new.`receive_status`,
`name`=new.`name`,`phone`=new.`phone`,`province`=new.`province`,`city`=new.`city`,`region`=new.`region`,`detail_address`=new.`detail_address`;

INSERT INTO `cms_prefrence_area` (`id`,`name`,`sub_title`,`pic`,`sort`,`show_status`) VALUES
(1,'数码精选','本地演示热门数码商品','',1,1),(2,'品质生活','精选家居与生活好物','',2,1)
AS new ON DUPLICATE KEY UPDATE
`name`=new.`name`,`sub_title`=new.`sub_title`,`pic`=new.`pic`,`sort`=new.`sort`,`show_status`=new.`show_status`;

-- 自有本地演示账号。密码分别为 Admin@123 与 Demo@123，仅用于本地开发。
INSERT INTO `ums_admin`
(`id`,`username`,`password`,`email`,`nick_name`,`note`,`status`) VALUES
(1,'admin','$2a$10$uHxKddTwwdOPaO7t9xhaSeThBrg6JpDYEvp2B5tjAfhdCNXjbClr2',
 'admin@ai-mall.local','AI-Mall 管理员','本地演示账号',1) AS new
ON DUPLICATE KEY UPDATE
`username`=new.`username`,`password`=new.`password`,`email`=new.`email`,
`nick_name`=new.`nick_name`,`note`=new.`note`,`status`=new.`status`;

INSERT INTO `ums_role`
(`id`,`name`,`description`,`admin_count`,`status`,`sort`) VALUES
(1,'超级管理员','AI-Mall 本地演示全权限角色',1,1,100) AS new
ON DUPLICATE KEY UPDATE
`name`=new.`name`,`description`=new.`description`,`admin_count`=new.`admin_count`,
`status`=new.`status`,`sort`=new.`sort`;

INSERT INTO `ums_admin_role_relation` (`id`,`admin_id`,`role_id`) VALUES (1,1,1) AS new
ON DUPLICATE KEY UPDATE `admin_id`=new.`admin_id`,`role_id`=new.`role_id`;

INSERT INTO `ums_menu`
(`id`,`parent_id`,`title`,`level`,`sort`,`name`,`icon`,`hidden`) VALUES
(1,0,'商品',0,100,'pms','product',0),
(2,0,'订单',0,90,'oms','order',0),
(3,0,'营销',0,80,'sms','sms',0),
(4,0,'AI Agent',0,70,'agent','sms',0),
(5,0,'权限',0,60,'ums','ums',0),
(11,1,'商品列表',1,100,'product','product-list',0),
(12,1,'添加商品',1,90,'addProduct','product-add',0),
(13,1,'商品分类',1,80,'productCate','product-cate',0),
(14,1,'商品类型',1,70,'productAttr','product-attr',0),
(15,1,'品牌管理',1,60,'brand','product-brand',0),
(16,1,'添加品牌',1,50,'addBrand','product-add',0),
(21,2,'订单列表',1,100,'order','product-list',0),
(22,2,'订单设置',1,90,'orderSetting','order-setting',0),
(23,2,'退货申请',1,80,'returnApply','order-return',0),
(24,2,'退货原因',1,70,'returnReason','order-return-reason',0),
(31,3,'秒杀活动',1,100,'flash','sms-flash',0),
(32,3,'优惠券',1,90,'coupon','sms-coupon',0),
(33,3,'添加优惠券',1,80,'addCoupon','sms-coupon',0),
(34,3,'品牌推荐',1,70,'homeBrand','product-brand',0),
(35,3,'新品推荐',1,60,'homeNew','sms-new',0),
(36,3,'人气推荐',1,50,'homeHot','sms-hot',0),
(37,3,'专题推荐',1,40,'homeSubject','sms-subject',0),
(38,3,'广告列表',1,30,'homeAdvertise','sms-ad',0),
(39,3,'添加广告',1,20,'addHomeAdvertise','sms-ad',0),
(41,4,'智能客服',1,100,'agentCustomer','sms-coupon',0),
(42,4,'智能运维',1,90,'agentOps','sms-flash',0),
(43,4,'自动化测试',1,80,'agentTest','product-attr',0),
(51,5,'用户列表',1,100,'admin','ums-admin',0),
(52,5,'角色列表',1,90,'role','ums-role',0),
(53,5,'菜单列表',1,80,'menu','ums-menu',0),
(54,5,'资源列表',1,70,'resource','ums-resource',0) AS new
ON DUPLICATE KEY UPDATE
`parent_id`=new.`parent_id`,`title`=new.`title`,`level`=new.`level`,
`sort`=new.`sort`,`name`=new.`name`,`icon`=new.`icon`,`hidden`=new.`hidden`;

INSERT IGNORE INTO `ums_role_menu_relation` (`role_id`,`menu_id`)
SELECT 1, m.id FROM `ums_menu` m
WHERE m.id IN (1,2,3,4,5,11,12,13,14,15,16,21,22,23,24,31,32,33,34,35,36,37,38,39,41,42,43,51,52,53,54);

INSERT INTO `ums_resource_category` (`id`,`name`,`sort`) VALUES
(1,'后台管理',100) AS new
ON DUPLICATE KEY UPDATE `name`=new.`name`,`sort`=new.`sort`;

INSERT INTO `ums_resource` (`id`,`name`,`url`,`description`,`category_id`) VALUES
(1,'管理员接口','/admin/**','后台账号管理',1),
(2,'商品接口','/product/**','商品管理',1),
(3,'品牌接口','/brand/**','品牌管理',1),
(4,'商品分类接口','/productCategory/**','商品分类管理',1),
(5,'商品属性接口','/productAttribute/**','商品属性管理',1),
(6,'订单接口','/order/**','订单管理',1),
(7,'营销接口','/coupon/**','优惠券管理',1),
(8,'权限接口','/role/**','角色管理',1),
(9,'菜单接口','/menu/**','菜单管理',1),
(10,'资源接口','/resource/**','资源管理',1),
(11,'其他后台接口','/**','本地演示兜底权限',1) AS new
ON DUPLICATE KEY UPDATE
`name`=new.`name`,`url`=new.`url`,`description`=new.`description`,`category_id`=new.`category_id`;

INSERT IGNORE INTO `ums_role_resource_relation` (`role_id`,`resource_id`)
SELECT 1, r.id FROM `ums_resource` r WHERE r.id BETWEEN 1 AND 11;

INSERT INTO `ums_member_level`
(`id`,`name`,`growth_point`,`default_status`,`free_freight_point`,`comment_growth_point`,
 `priviledge_free_freight`,`priviledge_sign_in`,`priviledge_comment`,`priviledge_promotion`,
 `priviledge_member_price`,`priviledge_birthday`,`note`) VALUES
(1,'普通会员',0,1,199.00,5,0,1,1,1,1,1,'AI-Mall 默认会员等级') AS new
ON DUPLICATE KEY UPDATE `name`=new.`name`,`default_status`=new.`default_status`;

INSERT INTO `ums_member`
(`id`,`member_level_id`,`username`,`password`,`nickname`,`phone`,`email`,`gender`,`status`) VALUES
(1,1,'demo','$2a$10$NtTg1GPCxGhoLXy1Fus7IeIOpxTj2/cnjTIw7t8f3vB84p2hJkct6',
 'AI-Mall 演示用户','13800138000','demo@ai-mall.local',1,1);

INSERT INTO `ums_member_receive_address`
(`id`,`member_id`,`name`,`phone_number`,`default_status`,`post_code`,`province`,`city`,`region`,`detail_address`) VALUES
(1,1,'演示用户','13800138000',1,'100000','北京市','北京市','海淀区','中关村 AI-Mall 演示地址') AS new
ON DUPLICATE KEY UPDATE `member_id`=new.`member_id`,`name`=new.`name`,
`phone_number`=new.`phone_number`,`default_status`=new.`default_status`,
`detail_address`=new.`detail_address`;

INSERT INTO `oms_order_setting`
(`id`,`flash_order_overtime`,`normal_order_overtime`,`confirm_overtime`,`finish_overtime`,`comment_overtime`) VALUES
(1,60,120,15,7,7) AS new
ON DUPLICATE KEY UPDATE `normal_order_overtime`=new.`normal_order_overtime`;

-- 演示订单（会员 demo 名下），供会员端智能客服"订单查询/售后工单"演示与后台订单页展示。
-- 订单编号 202609300001 是客服页面快捷问题使用的演示订单；AM-DEMO-* 两条与退货申请种子数据对应。
DELETE FROM `oms_order_item` WHERE `order_id` IN (1,2,3,4);
DELETE FROM `oms_order` WHERE `id` IN (1,2,3,4);
INSERT INTO `oms_order`
(`id`,`member_id`,`order_sn`,`create_time`,`member_username`,`total_amount`,`pay_amount`,`freight_amount`,`status`,`pay_type`,
 `delivery_company`,`delivery_sn`,`receiver_name`,`receiver_phone`,`receiver_province`,`receiver_city`,`receiver_region`,`receiver_detail_address`,
 `note`,`auto_confirm_day`,`integration`,`growth`,`delete_status`,`confirm_status`,`payment_time`,`delivery_time`,`receive_time`,`source_type`,`order_type`) VALUES
(1,1,'AM-DEMO-20261001-001','2026-10-01 09:20:00','demo',6499.00,6499.00,0.00,3,1,
 '顺丰速运','SF138000000001','演示用户','13800138000','北京市','北京市','海淀区','中关村 AI-Mall 演示地址',
 '本地演示订单（客服退货申请示例）',15,649,649,0,0,'2026-10-01 09:22:00','2026-10-01 15:00:00','2026-10-02 12:00:00',0,0),
(2,1,'AM-DEMO-20261001-002','2026-10-01 10:10:00','demo',3699.00,3699.00,0.00,3,1,
 '顺丰速运','SF138000000002','演示用户','13800138000','北京市','北京市','海淀区','中关村 AI-Mall 演示地址',
 '本地演示订单（客服换货申请示例）',15,369,369,0,0,'2026-10-01 10:12:00','2026-10-01 16:00:00','2026-10-02 13:00:00',0,0),
(3,1,'202609300001','2026-09-30 15:30:00','demo',6499.00,6499.00,0.00,2,1,
 '顺丰速运','SF202609300001','演示用户','13800138000','北京市','北京市','海淀区','中关村 AI-Mall 演示地址',
 '会员端智能客服订单查询演示订单',15,649,649,0,0,'2026-09-30 15:32:00','2026-09-30 20:10:00',NULL,0,0),
(4,1,'202609300002','2026-09-30 18:00:00','demo',899.00,899.00,0.00,1,1,
 NULL,NULL,'演示用户','13800138000','北京市','北京市','海淀区','中关村 AI-Mall 演示地址',
 '待发货演示订单',15,89,89,0,0,'2026-09-30 18:01:00',NULL,NULL,0,0);

INSERT INTO `oms_order_item`
(`order_id`,`order_sn`,`product_id`,`product_name`,`product_pic`,`product_price`,`product_quantity`,`product_sku_id`,`product_sn`,`product_sku_code`,`product_category_id`,`real_amount`,`product_brand`,`product_attr`) VALUES
(1,'AM-DEMO-20261001-001',1,'华为Mate60 Pro 旗舰手机5G','',6499.00,1,NULL,'HW-MATE60PRO','HW-MATE60PRO-01',4,6499.00,'华为','颜色：青竹色；版本：12GB+512GB'),
(2,'AM-DEMO-20261001-002',2,'小米14 骁龙8Gen3 智能手机','',3699.00,1,NULL,'MI-14','MI-14-01',4,3699.00,'小米','颜色：黑色；版本：12GB+256GB'),
(3,'202609300001',1,'华为Mate60 Pro 旗舰手机5G','',6499.00,1,NULL,'HW-MATE60PRO','HW-MATE60PRO-01',4,6499.00,'华为','颜色：青竹色；版本：12GB+512GB'),
(4,'202609300002',7,'华为FreeBuds Pro 3 无线降噪耳机','',899.00,1,NULL,'HW-FBP3','HW-FBP3-01',6,899.00,'华为','颜色：陶瓷白');

INSERT INTO `ums_integration_consume_setting`
(`id`,`deduction_per_amount`,`max_percent_per_order`,`use_unit`,`coupon_status`) VALUES
(1,100,50,100,1) AS new
ON DUPLICATE KEY UPDATE `deduction_per_amount`=new.`deduction_per_amount`;

-- 品牌
INSERT INTO `pms_brand` (`id`,`name`,`first_letter`,`sort`,`factory_status`,`show_status`,`product_count`,`product_comment_count`,`logo`,`big_pic`) VALUES
(1,'华为','H',10,1,1,3,20,'',''),
(2,'小米','X',9,1,1,2,15,'',''),
(3,'Apple','A',8,1,1,2,30,'',''),
(4,'索尼','S',7,1,1,1,8,'',''),
(5,'优衣库','Y',6,1,1,1,5,'',''),
(6,'李宁','L',5,1,1,1,6,'',''),
(7,'三只松鼠','S',4,1,1,1,12,'',''),
(8,'良品铺子','L',3,1,1,1,9,'','');

-- 商品分类（含二级）
INSERT INTO `pms_product_category` (`id`,`parent_id`,`name`,`level`,`product_unit`,`sort`,`show_status`,`nav_status`,`product_count`,`product_attribute_count`) VALUES
(1,0,'手机数码',1,'件',0,1,1,6,4),
(2,0,'服装鞋帽',1,'件',1,1,1,2,2),
(3,0,'食品饮料',1,'件',2,1,1,2,2),
(4,1,'智能手机',2,'件',1,1,0,4,4),
(5,1,'平板电脑',2,'件',2,1,0,2,2),
(6,1,'耳机音响',2,'件',3,1,0,2,3),
(7,2,'男装',2,'件',1,1,0,1,2),
(8,2,'运动鞋服',2,'件',2,1,0,1,2),
(9,3,'坚果零食',2,'件',1,1,0,2,2);

-- 商品（含冗余 brand_name / product_category_name，ES 导入直接读）
INSERT INTO `pms_product`
(`id`,`brand_id`,`product_category_id`,`feight_template_id`,`product_attribute_category_id`,`name`,`pic`,`product_sn`,`delete_status`,`publish_status`,`new_status`,`recommand_status`,`verify_status`,`sort`,`sale`,`price`,`promotion_price`,`gift_growth`,`gift_point`,`use_point_limit`,`sub_title`,`original_price`,`stock`,`low_stock`,`unit`,`weight`,`service_ids`,`keywords`,`note`,`album_pics`,`promotion_type`,`brand_name`,`product_category_name`)
VALUES
(1,1,4,1,1,'华为Mate60 Pro 旗舰手机5G',' ','HW-MATE60PRO',0,1,1,1,1,1,1200,6999.00,6499.00,500,500,0,'超可靠玄武架构 卫星通信 昆仑玻璃',7999.00,500,10,'台',0.21,'1,2,3','华为,Mate60,旗舰,5G,麒麟9000,卫星通信','压测主推款',',',0,'华为','智能手机'),
(2,2,4,1,1,'小米14 骁龙8Gen3 智能手机',' ','MI-14',0,1,1,1,1,2,3000,3999.00,3699.00,400,400,0,'徕卡光学镜头 小尺寸旗舰',4599.00,800,10,'台',0.19,'1,2,3','小米,小米14,骁龙8Gen3,徕卡,旗舰','压测主推款',',',0,'小米','智能手机'),
(3,3,4,1,1,'Apple iPhone 15 智能手机',' ','AP-IP15',0,1,1,1,1,3,2500,5999.00,5499.00,600,600,0,'A16仿生 4800万像素主摄',6999.00,300,10,'台',0.17,'1,2,3','苹果,iPhone15,智能手机,ios','压测主推款',',',0,'Apple','智能手机'),
(4,2,4,1,1,'小米Redmi K70 电竞版智能手机',' ','MI-K70',0,1,1,0,1,4,2000,2499.00,2299.00,300,300,0,'第二代骁龙8 2K直屏',2999.00,1000,10,'台',0.21,'1,2,3','红米,K70,电竞,骁龙8gen2','压测主推款',',',0,'小米','智能手机'),
(5,1,5,1,2,'华为MatePad Pro 13.2英寸平板',' ','HW-MPPRO',0,1,1,1,1,1,600,3599.00,3299.00,400,400,0,'星闪连接 柔性OLED',4599.00,200,10,'台',0.45,'1,2,3','华为,平板,MatePad,OLED,星闪','压测主推款',',',0,'华为','平板电脑'),
(6,3,5,1,2,'Apple iPad Air 11英寸平板',' ','AP-IPAIR',0,1,0,0,1,2,400,4399.00,3999.00,500,500,0,'M2芯片 超薄全面屏',5199.00,150,10,'台',0.46,'1,2,3','苹果,iPad,平板,air','压测主推款',',',0,'Apple','平板电脑'),
(7,1,6,1,3,'华为FreeBuds Pro 3 无线降噪耳机',' ','HW-FBP3',0,1,1,1,1,1,800,999.00,899.00,200,200,0,'星闪连接 智慧动态降噪',1299.00,1000,10,'副',0.06,'1,2,3','华为,耳机,FreeBuds,降噪,无线','压测主推款',',',0,'华为','耳机音响'),
(8,4,6,1,3,'索尼WH-1000XM5 头戴式降噪耳机',' ','SONY-XM5',0,1,0,1,1,2,500,2299.00,1999.00,300,300,0,'行业标杆降噪 30小时续航',2899.00,200,10,'副',0.25,'1,2,3','索尼,耳机,降噪,XM5,头戴','压测主推款',',',0,'索尼','耳机音响'),
(9,5,7,1,4,'优衣库男装 圆领纯棉T恤',' ','UNI-TSHIRT',0,1,1,1,1,1,5000,79.00,59.00,50,50,0,'新疆长绒棉 透气百搭',99.00,5000,100,'件',0.15,'1,2,3','优衣库,T恤,纯棉,男装,圆领','压测主推款',',',0,'优衣库','男装'),
(10,6,8,1,5,'李宁跑步鞋 减震回弹运动鞋',' ','LN-RUN',0,1,0,0,1,1,1500,499.00,419.00,100,100,0,'䨻科技中底 马拉松竞速',699.00,2000,100,'双',0.32,'1,2,3','李宁,跑步鞋,运动鞋,减震,䨻','压测主推款',',',0,'李宁','运动鞋服'),
(11,7,9,1,6,'三只松鼠每日坚果750g混合装',' ','SS-30DAY',0,1,1,1,1,1,4000,89.00,79.90,30,30,0,'30包独立小包装 孕妇儿童健康零食',129.00,3000,100,'盒',0.75,'1,2,3','三只松鼠,坚果,每日坚果,零食,混合装','压测主推款',',',0,'三只松鼠','坚果零食'),
(12,8,9,1,6,'良品铺子山核桃仁1000g',' ','LP-SHT',0,1,0,0,1,2,1000,129.00,109.00,50,50,0,'奶油味手剥薄皮大颗坚果',169.00,1500,100,'盒',1.00,'1,2,3','良品铺子,山核桃,坚果,零食','压测主推款',',',0,'良品铺子','坚果零食');

-- 商品 SKU（sp_data 为 JSON 销售属性，前端按此渲染规格选择）
INSERT INTO `pms_sku_stock` (`product_id`,`sku_code`,`price`,`stock`,`low_stock`,`pic`,`sale`,`promotion_price`,`lock_stock`,`sp_data`) VALUES
(1,'HW-MATE60PRO-01',6999.00,200,5,'',600,6499.00,0,'{"颜色":"青竹色","版本":"12GB+512GB"}'),
(1,'HW-MATE60PRO-02',6999.00,300,5,'',600,6499.00,0,'{"颜色":"曜金黑","版本":"12GB+512GB"}'),
(2,'MI-14-01',3999.00,400,5,'',1500,3699.00,0,'{"颜色":"黑色","版本":"12GB+256GB"}'),
(2,'MI-14-02',3999.00,400,5,'',1500,3699.00,0,'{"颜色":"白色","版本":"16GB+512GB"}'),
(3,'AP-IP15-01',5999.00,300,5,'',2500,5499.00,0,'{"颜色":"蓝色","版本":"128GB"}'),
(4,'MI-K70-01',2499.00,1000,5,'',2000,2299.00,0,'{"颜色":"墨羽","版本":"16GB+512GB"}'),
(5,'HW-MPPRO-01',3599.00,200,5,'',600,3299.00,0,'{"颜色":"雅川青","版本":"12GB+512GB"}'),
(6,'AP-IPAIR-01',4399.00,150,5,'',400,3999.00,0,'{"颜色":"星光色","版本":"128GB"}'),
(7,'HW-FBP3-01',999.00,1000,5,'',800,899.00,0,'{"颜色":"陶瓷白"}'),
(8,'SONY-XM5-01',2299.00,200,5,'',500,1999.00,0,'{"颜色":"黑色"}'),
(9,'UNI-TSHIRT-01',79.00,2000,20,'',1800,59.00,0,'{"颜色":"白色","尺码":"M"}'),
(9,'UNI-TSHIRT-02',79.00,2000,20,'',1800,59.00,0,'{"颜色":"白色","尺码":"L"}'),
(9,'UNI-TSHIRT-03',79.00,1000,20,'',1400,59.00,0,'{"颜色":"黑色","尺码":"M"}'),
(10,'LN-RUN-01',499.00,2000,20,'',1500,419.00,0,'{"颜色":"黑武士","尺码":"42"}'),
(11,'SS-30DAY-01',89.00,3000,50,'',4000,79.90,0,'{"规格":"750g/30包"}'),
(12,'LP-SHT-01',129.00,1500,50,'',1000,109.00,0,'{"规格":"1000g"}');

-- 商品属性（分类：1手机 2平板 3耳机 4男装 5运动鞋 6零食）
INSERT INTO `pms_product_attribute`
(`id`,`product_attribute_category_id`,`name`,`select_type`,`input_type`,`input_list`,`sort`,`filter_type`,`search_type`,`related_status`,`hand_add_status`,`type`) VALUES
(1,1,'运行内存',1,1,'8GB,12GB,16GB',1,0,1,1,1,0),
(2,1,'机身存储',1,1,'128GB,256GB,512GB,1TB',2,0,1,1,1,0),
(3,1,'屏幕尺寸',1,1,'6.1英寸,6.5英寸,6.7英寸',3,1,2,1,1,1),
(4,1,'电池容量',1,1,'4500mAh,5000mAh,5500mAh',4,1,2,1,1,1),
(5,1,'摄像头',1,1,'4800万像素,5000万像素,一亿像素',5,1,1,1,1,1),
(6,2,'屏幕尺寸',1,1,'10.4英寸,11英寸,12.9英寸,13.2英寸',1,1,2,1,1,1),
(7,2,'存储容量',1,1,'128GB,256GB,512GB',2,0,1,1,1,0),
(8,2,'网络制式',1,1,'WiFi版,5G蜂窝版',3,0,1,1,1,0),
(9,3,'佩戴方式',1,1,'入耳式,头戴式',1,1,1,1,1,1),
(10,3,'续航时间',1,1,'20小时,25小时,30小时',2,1,2,1,1,1),
(11,3,'蓝牙版本',1,1,'5.2,5.3',3,0,1,1,1,0),
(12,4,'尺码',1,1,'S,M,L,XL,XXL',1,0,1,1,1,0),
(13,4,'颜色',1,1,'白色,黑色,藏青色',2,1,1,1,1,0),
(14,5,'尺码',1,1,'39,40,41,42,43,44',1,0,1,1,1,0),
(15,5,'颜色',1,1,'黑武士,白雾灰',2,1,1,1,1,0),
(16,6,'净含量',1,1,'500g,750g,1000g',1,0,1,1,1,0),
(17,6,'保质期',1,1,'6个月,12个月',2,1,2,1,1,1);

-- 商品属性值（与 SKU 销售属性同语言，供详情/购物车渲染）
INSERT INTO `pms_product_attribute_value` (`product_id`,`product_attribute_id`,`value`) VALUES
(1,1,'12GB'),(1,2,'512GB'),(1,3,'6.7英寸'),(1,4,'5000mAh'),(1,5,'5000万像素'),
(2,1,'12GB'),(2,2,'256GB'),(2,3,'6.36英寸'),(2,4,'4500mAh'),(2,5,'5000万像素'),
(3,1,'8GB'),(3,2,'128GB'),(3,3,'6.1英寸'),(3,4,'4500mAh'),(3,5,'4800万像素'),
(4,1,'16GB'),(4,2,'512GB'),(4,3,'6.67英寸'),(4,4,'5000mAh'),(4,5,'5000万像素'),
(5,6,'13.2英寸'),(5,7,'512GB'),(5,8,'WiFi版'),
(6,6,'11英寸'),(6,7,'128GB'),(6,8,'WiFi版'),
(7,9,'入耳式'),(7,10,'30小时'),(7,11,'5.3'),
(8,9,'头戴式'),(8,10,'30小时'),(8,11,'5.2'),
(9,12,'M'),(9,13,'白色'),
(10,14,'42'),(10,15,'黑武士'),
(11,16,'750g'),(11,17,'12个月'),
(12,16,'1000g'),(12,17,'12个月');

-- 优惠券：三种使用类型各备一张，保证 detail 接口的可用券查询有真实数据
INSERT INTO `sms_coupon`
(`id`,`type`,`name`,`platform`,`count`,`amount`,`per_limit`,`min_point`,`start_time`,`end_time`,`use_type`,`note`,`publish_count`,`use_count`,`receive_count`,`enable_time`,`code`,`member_level`) VALUES
(1,0,'满99减10元全品类券',0,10000,10.00,1,99.00,'2026-09-01 00:00:00','2026-12-31 23:59:59',0,'用户下单实测用',10000,0,120,'2026-09-01 00:00:00','CAMPAIGN-10',0),
(2,0,'满3000减200元手机专享券',0,5000,200.00,1,3000.00,'2026-09-01 00:00:00','2026-12-31 23:59:59',1,'指定手机分类可用',5000,0,60,'2026-09-01 00:00:00','PHONE-200',0),
(3,0,'华为Mate60 Pro专属券',0,1000,300.00,1,5000.00,'2026-09-01 00:00:00','2026-12-31 23:59:59',2,'指定商品可用',1000,0,15,'2026-09-01 00:00:00','HWM60-300',0);

INSERT INTO `sms_coupon_product_category_relation` (`coupon_id`,`product_category_id`,`product_category_name`,`parent_category_name`) VALUES
(2,4,'智能手机','手机数码');

INSERT INTO `sms_coupon_product_relation` (`coupon_id`,`product_id`,`product_name`,`product_sn`) VALUES
(3,1,'华为Mate60 Pro 旗舰手机5G','HW-MATE60PRO');

-- 首页广告（type=1 app 首页轮播，status=1 上线）
INSERT INTO `sms_home_advertise` (`name`,`type`,`pic`,`start_time`,`end_time`,`status`,`url`,`note`,`sort`) VALUES
('新品首发-华为Mate60 Pro',1,'','2026-09-01 00:00:00','2026-12-31 00:00:00',1,'','压测轮播1',3),
('闪购-小米14直降300',1,'','2026-09-01 00:00:00','2026-12-31 00:00:00',1,'','压测轮播2',2),
('每日坚果特卖',1,'','2026-09-01 00:00:00','2026-12-31 00:00:00',1,'','压测轮播3',1);

-- 首页推荐品牌
INSERT INTO `sms_home_brand` (`brand_id`,`brand_name`,`recommend_status`,`sort`) VALUES
(1,'华为',1,4),(2,'小米',1,3),(3,'Apple',1,2),(4,'索尼',1,1);

-- 新品推荐
INSERT INTO `sms_home_new_product` (`product_id`,`product_name`,`recommend_status`,`sort`) VALUES
(1,'华为Mate60 Pro 旗舰手机5G',1,4),(2,'小米14 骁龙8Gen3 智能手机',1,3),(5,'华为MatePad Pro 13.2英寸平板',1,2),(7,'华为FreeBuds Pro 3 无线降噪耳机',1,1);

-- 人气推荐
INSERT INTO `sms_home_recommend_product` (`product_id`,`product_name`,`recommend_status`,`sort`) VALUES
(3,'Apple iPhone 15 智能手机',1,4),(4,'小米Redmi K70 电竞版智能手机',1,3),(8,'索尼WH-1000XM5 头戴式降噪耳机',1,2),(11,'三只松鼠每日坚果750g混合装',1,1);

-- 秒杀（活动覆盖今天；场次含"当前场"与"下一场"，home 秒杀板块才有内容）
INSERT INTO `sms_flash_promotion` (`id`,`title`,`start_date`,`end_date`,`status`) VALUES
(1,'金秋数码大促','2026-09-01','2026-09-30',1);

INSERT INTO `sms_flash_promotion_session` (`id`,`name`,`start_time`,`end_time`,`status`) VALUES
(1,'16:00-18:00','16:00:00','18:00:00',1),
(2,'20:00-22:00','20:00:00','22:00:00',1);

INSERT INTO `sms_flash_promotion_product_relation`
(`flash_promotion_id`,`flash_promotion_session_id`,`product_id`,`flash_promotion_price`,`flash_promotion_count`,`flash_promotion_limit`,`sort`) VALUES
(1,1,1,6499.00,100,1,3),
(1,1,2,3699.00,100,1,2),
(1,1,3,5499.00,100,1,1);

-- 专题
INSERT INTO `cms_subject` (`id`,`category_id`,`title`,`pic`,`product_count`,`recommend_status`,`create_time`,`collect_count`,`read_count`,`comment_count`,`album_pics`,`description`,`show_status`,`forward_count`,`category_name`) VALUES
(1,1,'智能旗舰抢先购','',4,1,'2026-09-01 00:00:00',100,2000,50,',','华为/小米/苹果旗舰机型专题',1,30,'手机'),
(2,2,'轻食坚果周','',2,1,'2026-09-01 00:00:00',80,1500,30,',','健康零食联合专题',1,20,'食品');

INSERT INTO `sms_home_recommend_subject` (`subject_id`,`subject_name`,`recommend_status`,`sort`) VALUES
(1,'智能旗舰抢先购',1,2),(2,'轻食坚果周',1,1);
