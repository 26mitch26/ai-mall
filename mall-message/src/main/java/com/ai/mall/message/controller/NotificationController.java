package com.ai.mall.message.controller;

import com.ai.mall.message.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 通知API - 站内信、邮件、短信消息管理
 */
@Tag(name = "通知管理", description = "站内信、邮件、短信等通知接口")
@RestController
@RequestMapping("/api/notification")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @Operation(summary = "发送站内信")
    @PostMapping("/site-message")
    public String sendSiteMessage(@RequestParam Long userId,
                                  @RequestParam String title,
                                  @RequestParam String content) {
        notificationService.sendSiteMessage(userId, title, content);
        return "站内信发送成功";
    }

    @Operation(summary = "发送邮件")
    @PostMapping("/email")
    public String sendEmail(@RequestParam String to,
                            @RequestParam String subject,
                            @RequestParam String body) {
        notificationService.sendEmail(to, subject, body);
        return "邮件发送成功";
    }

    @Operation(summary = "发送短信")
    @PostMapping("/sms")
    public String sendSms(@RequestParam String phone,
                          @RequestParam String templateCode,
                          @RequestParam(required = false) String params) {
        notificationService.sendSms(phone, templateCode, params);
        return "短信发送成功";
    }

    @Operation(summary = "批量通知")
    @PostMapping("/broadcast")
    public String broadcast(@RequestParam NotificationService.Channel channel,
                            @RequestBody List<Long> userIds,
                            @RequestParam String title,
                            @RequestParam String content) {
        notificationService.broadcast(channel, userIds, title, content);
        return "批量通知发送成功";
    }
}
