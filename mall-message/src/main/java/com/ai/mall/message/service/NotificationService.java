package com.ai.mall.message.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 通知服务 - 支持站内信、邮件、短信等多渠道消息推送
 */
@Slf4j
@Service
public class NotificationService {

    /**
     * 通知渠道枚举
     */
    public enum Channel {
        SITE_MESSAGE,
        EMAIL,
        SMS
    }

    /**
     * 发送站内信
     */
    public void sendSiteMessage(Long userId, String title, String content) {
        log.info("发送站内信: userId={}, title={}", userId, title);
        // TODO: 持久化站内信记录到数据库
    }

    /**
     * 发送邮件
     */
    public void sendEmail(String to, String subject, String body) {
        log.info("发送邮件: to={}, subject={}", to, subject);
        // TODO: 通过JavaMailSender发送邮件
    }

    /**
     * 发送短信
     */
    public void sendSms(String phone, String templateCode, String params) {
        log.info("发送短信: phone={}, template={}", phone, templateCode);
        // TODO: 对接短信服务商API
    }

    /**
     * 多渠道批量通知
     */
    public void broadcast(Channel channel, List<Long> userIds, String title, String content) {
        log.info("批量通知: channel={}, userCount={}", channel, userIds.size());
        for (Long userId : userIds) {
            switch (channel) {
                case SITE_MESSAGE -> sendSiteMessage(userId, title, content);
                case EMAIL -> log.info("邮件通知: userId={}", userId);
                case SMS -> log.info("短信通知: userId={}", userId);
            }
        }
    }
}
