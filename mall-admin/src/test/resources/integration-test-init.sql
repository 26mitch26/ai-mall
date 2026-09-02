-- Mall Integration Test initialization
CREATE DATABASE IF NOT EXISTS `mall_test` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE `mall_test`;

-- 商品分类表
CREATE TABLE IF NOT EXISTS `pms_product_category` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `parent_id` bigint DEFAULT 0 COMMENT '父分类的编号',
  `name` varchar(64) DEFAULT NULL COMMENT '分类名称',
  `level` int DEFAULT NULL COMMENT '分类级别',
  `product_unit` varchar(64) DEFAULT NULL COMMENT '商品单位',
  `icon` varchar(255) DEFAULT NULL COMMENT '图标',
  `sort` int DEFAULT NULL COMMENT '排序',
  `show_status` int DEFAULT NULL COMMENT '显示状态',
  `product_count` int DEFAULT NULL COMMENT '商品数量',
  `product_attribute_count` int DEFAULT NULL COMMENT '商品属性数量',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品分类表';

-- 商品表
CREATE TABLE IF NOT EXISTS `pms_product` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `brand_id` bigint DEFAULT NULL COMMENT '品牌id',
  `product_category_id` bigint DEFAULT NULL COMMENT '商品分类id',
  `name` varchar(64) DEFAULT NULL COMMENT '商品名称',
  `sub_title` varchar(255) DEFAULT NULL COMMENT '副标题',
  `price` decimal(10,2) DEFAULT NULL COMMENT '价格',
  `stock` int DEFAULT NULL COMMENT '库存',
  `unit` varchar(16) DEFAULT NULL COMMENT '单位',
  `weight` decimal(10,2) DEFAULT NULL COMMENT '重量',
  `status` int DEFAULT NULL COMMENT '上架状态',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品信息表';

-- 商品会员价格表
CREATE TABLE IF NOT EXISTS `pms_member_price` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `product_id` bigint DEFAULT NULL,
  `member_level_id` bigint DEFAULT NULL,
  `member_price` decimal(10,2) DEFAULT NULL COMMENT '会员价格',
  `member_level_name` varchar(100) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品会员价格表';

-- 产品阶梯价格表
CREATE TABLE IF NOT EXISTS `pms_product_ladder` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `product_id` bigint DEFAULT NULL,
  `count` int DEFAULT NULL COMMENT '满足的商品数量',
  `discount` decimal(10,2) DEFAULT NULL COMMENT '折扣',
  `price` decimal(10,2) DEFAULT NULL COMMENT '折后价格',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产品阶梯价格表';

-- 产品满减表
CREATE TABLE IF NOT EXISTS `pms_product_full_reduction` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `product_id` bigint DEFAULT NULL,
  `full_price` decimal(10,2) DEFAULT NULL,
  `reduce_price` decimal(10,2) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产品满减表';

-- 商品库存表
CREATE TABLE IF NOT EXISTS `pms_sku_stock` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `product_id` bigint DEFAULT NULL,
  `sku_code` varchar(64) DEFAULT NULL COMMENT 'sku编码',
  `price` decimal(10,2) DEFAULT NULL,
  `stock` int DEFAULT NULL COMMENT '库存',
  `low_stock` int DEFAULT NULL COMMENT '预警库存',
  `pic` varchar(255) DEFAULT NULL COMMENT '展示图片',
  `sale` int DEFAULT NULL COMMENT '销量',
  `promotion_price` decimal(10,2) DEFAULT NULL COMMENT '单品促销价格',
  `sp_data` varchar(500) DEFAULT NULL COMMENT '锁定库存',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品库存表';

-- 商品属性表
CREATE TABLE IF NOT EXISTS `pms_product_attribute` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `product_attribute_category_id` bigint DEFAULT NULL,
  `name` varchar(64) DEFAULT NULL,
  `select_type` int DEFAULT NULL COMMENT '属性选择类型',
  `input_type` int DEFAULT NULL COMMENT '属性录入方式',
  `input_list` varchar(255) DEFAULT NULL COMMENT '可选值列表',
  `sort` int DEFAULT NULL,
  `filter_type` int DEFAULT NULL COMMENT '分类筛选样式',
  `search_type` int DEFAULT NULL COMMENT '检索类型',
  `related_status` int DEFAULT NULL COMMENT '相同属性产品是否关联',
  `hand_add_status` int DEFAULT NULL COMMENT '是否支持手动新增',
  `type` int DEFAULT NULL COMMENT '属性的类型',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品属性表';

-- 商品属性分类表
CREATE TABLE IF NOT EXISTS `pms_product_attribute_category` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(64) DEFAULT NULL,
  `attribute_count` int DEFAULT 0,
  `param_count` int DEFAULT 0,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品属性分类表';

-- 商品属性值表
CREATE TABLE IF NOT EXISTS `pms_product_attribute_value` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `product_id` bigint DEFAULT NULL,
  `product_attribute_id` bigint DEFAULT NULL,
  `value` varchar(64) DEFAULT NULL COMMENT '手动添加规格或参数的值，参数单值，规格有多个值',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品属性值表';

-- 专题商品关系表
CREATE TABLE IF NOT EXISTS `cms_subject_product_relation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `subject_id` bigint DEFAULT NULL,
  `product_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='专题商品关系表';

-- 优选专区商品关系表
CREATE TABLE IF NOT EXISTS `cms_prefrence_area_product_relation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `prefrence_area_id` bigint DEFAULT NULL,
  `product_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='优选专区商品关系表';

-- 会员表
CREATE TABLE IF NOT EXISTS `ums_member` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_level_id` bigint DEFAULT NULL,
  `username` varchar(64) DEFAULT NULL,
  `password` varchar(64) DEFAULT NULL,
  `phone` varchar(64) DEFAULT NULL,
  `status` int DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员表';

-- 品牌表
CREATE TABLE IF NOT EXISTS `pms_brand` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(64) DEFAULT NULL,
  `first_letter` varchar(8) DEFAULT NULL,
  `sort` int DEFAULT NULL,
  `show_status` int DEFAULT NULL,
  `product_count` int DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='品牌表';

-- 插入测试数据
INSERT INTO `pms_product_category` (`id`, `name`, `level`, `sort`, `show_status`) VALUES
(1, '手机数码', 1, 0, 1),
(2, '服装鞋帽', 1, 1, 1);

INSERT INTO `pms_brand` (`id`, `name`, `first_letter`, `sort`, `show_status`) VALUES
(1, '测试品牌', 'T', 0, 1);

INSERT INTO `pms_product` (`id`, `brand_id`, `product_category_id`, `name`, `price`, `stock`, `status`) VALUES
(1, 1, 1, '测试手机', 2999.00, 100, 1),
(2, 1, 1, '测试耳机', 199.00, 500, 1);

INSERT INTO `pms_member_price` (`product_id`, `member_level_id`, `member_price`, `member_level_name`) VALUES
(1, 1, 2799.00, '黄金会员'),
(1, 2, 2599.00, '铂金会员');