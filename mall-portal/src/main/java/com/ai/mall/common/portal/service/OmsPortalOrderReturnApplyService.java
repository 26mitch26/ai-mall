package com.ai.mall.portal.service;

import com.ai.mall.portal.domain.OmsOrderReturnApplyParam;
import com.ai.mall.model.OmsOrderReturnApply;

/**
 * 前台订单退货管理Service
 * Created by macro on 2018/10/17.
 */
public interface OmsPortalOrderReturnApplyService {
    /**
     * 提交申请
     */
    int create(OmsOrderReturnApplyParam returnApply);

    /** Persist the application and return its generated primary key for idempotent replay. */
    OmsOrderReturnApply createAndReturn(OmsOrderReturnApplyParam returnApply);
}
