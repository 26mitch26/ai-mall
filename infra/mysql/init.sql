-- AI-Mall 数据库初始化脚本

-- 创建数据库
CREATE DATABASE IF NOT EXISTS `mall` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

USE `mall`;

-- 用户表
CREATE TABLE IF NOT EXISTS `ums_member` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_level_id` bigint DEFAULT NULL,
  `username` varchar(64) DEFAULT NULL COMMENT '用户名',
  `password` varchar(64) DEFAULT NULL COMMENT '密码',
  `phone` varchar(64) DEFAULT NULL COMMENT '手机号',
  `email` varchar(64) DEFAULT NULL COMMENT '邮箱',
  `icon` varchar(500) DEFAULT NULL COMMENT '头像',
  `gender` int DEFAULT NULL COMMENT '性别',
  `birth` date DEFAULT NULL COMMENT '生日',
  `city` varchar(64) DEFAULT NULL COMMENT '所在城市',
  `job` varchar(100) DEFAULT NULL COMMENT '职业',
  `sign` varchar(200) DEFAULT NULL COMMENT '个性签名',
  `source_type` int DEFAULT NULL COMMENT '用户来源',
  `integration` int DEFAULT NULL COMMENT '积分',
  `growth` int DEFAULT NULL COMMENT '成长值',
  `status` int DEFAULT NULL COMMENT '启用状态',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_username` (`username`),
  UNIQUE KEY `idx_phone` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员表';

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

-- 订单表
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

-- 订单商品信息表
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
  `product_sn` varchar(64) DEFAULT NULL COMMENT '商品sku编号',
  PRIMARY KEY (`id`),
  KEY `idx_order_id` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单商品信息表';

-- 插入测试数据
INSERT INTO `ums_member` (`username`, `password`, `phone`, `email`, `gender`, `status`) VALUES
('testuser', '123456', '13800138000', 'test@example.com', 1, 1);

INSERT INTO `pms_product_category` (`name`, `level`, `sort`, `show_status`, `icon`) VALUES
('手机数码', 1, 0, 1, ''),
('服装鞋帽', 1, 1, 1, ''),
('食品饮料', 1, 2, 1, '');

INSERT INTO `pms_product` (`brand_id`, `product_category_id`, `name`, `sub_title`, `price`, `stock`, `status`) VALUES
(1, 1, '测试手机', '这是一款测试手机', 2999.00, 100, 1),
(1, 1, '测试耳机', '这是一款测试耳机', 199.00, 500, 1),
(2, 2, '测试T恤', '这是一款测试T恤', 99.00, 1000, 1);
