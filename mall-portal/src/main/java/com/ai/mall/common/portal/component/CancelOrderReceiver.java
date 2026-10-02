package com.ai.mall.portal.component;

import com.ai.mall.portal.service.OmsPortalOrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 *  取消订单消息的接收者
 * Created by macro on 2018/9/14.
 *
 *  autoStartup 由配置决定：本机压测 / 无 RabbitMQ 容器时（spring.rabbitmq.listener.simple.auto-startup=false），
 *  监听器不启动，避免每 5s 无限重连刷爆日志与线程（曾使 portal.log 膨胀到 1.1GB，拖垮压测 QPS）。
 *  生产环境 RabbitMQ 在线，走默认 true 正常消费。
 */
@Component
@ConditionalOnBean(name = "rabbitListenerContainerFactory")
@RabbitListener(queues = "mall.order.cancel", autoStartup = "${spring.rabbitmq.listener.simple.auto-startup:true}")
public class CancelOrderReceiver {
    private static final Logger LOGGER = LoggerFactory.getLogger(CancelOrderReceiver.class);
    @Autowired
    private OmsPortalOrderService portalOrderService;
    @RabbitHandler
    public void handle(Long orderId){
        portalOrderService.cancelOrder(orderId);
        LOGGER.info("process orderId:{}",orderId);
    }
}
