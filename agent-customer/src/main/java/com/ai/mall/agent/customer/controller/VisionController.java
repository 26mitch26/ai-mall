package com.ai.mall.agent.customer.controller;

import com.ai.mall.agent.customer.service.security.MemberIdentityResolver;
import com.ai.mall.agent.customer.service.vision.AfterSaleVisionService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/vision")
public class VisionController {
    private final MemberIdentityResolver identity;
    private final AfterSaleVisionService vision;
    public VisionController(MemberIdentityResolver identity,AfterSaleVisionService vision) { this.identity=identity; this.vision=vision; }
    @PostMapping(value="/inspect",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public AfterSaleVisionService.Inspection inspect(@RequestPart MultipartFile file,
            @RequestHeader(value=HttpHeaders.AUTHORIZATION,required=false) String authorization) {
        if (!identity.resolve(null,authorization).isAuthenticated()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Member login required");
        return vision.inspect(file);
    }
}
