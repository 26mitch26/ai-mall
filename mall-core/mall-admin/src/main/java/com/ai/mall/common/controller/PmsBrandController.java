package com.ai.mall.controller;

import com.ai.mall.common.api.CommonPage;
import com.ai.mall.common.api.CommonResult;
import com.ai.mall.dto.PmsBrandParam;
import com.ai.mall.model.PmsBrand;
import com.ai.mall.service.PmsBrandService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 商品品牌管理Controller
 * Created by macro on 2018/4/26.
 */
@Controller
@Tag(name = "PmsBrandController", description = "商品品牌管理")
@RequestMapping("/brand")
public class PmsBrandController {
    @Autowired
    private PmsBrandService brandService;

    @Operation(summary =  "获取全部品牌列表")
    @RequestMapping(value = "/listAll", method = RequestMethod.GET)
    @ResponseBody
    public CommonResult<List<PmsBrand>> getList() {
        return CommonResult.success(brandService.listAllBrand());
    }

    @Operation(summary =  "添加品牌")
    @ApiResponse(responseCode = "200", description = "创建成功",
            content = @Content(schema = @Schema(implementation = CommonResult.class)))
    @ApiResponse(responseCode = "500", description = "创建失败")
    @RequestMapping(value = "/create", method = RequestMethod.POST)
    @ResponseBody
    public CommonResult create(@Validated @RequestBody PmsBrandParam pmsBrand) {
        CommonResult commonResult;
        int count = brandService.createBrand(pmsBrand);
        if (count == 1) {
            commonResult = CommonResult.success(count);
        } else {
            commonResult = CommonResult.failed();
        }
        return commonResult;
    }

    @Operation(summary =  "更新品牌")
    @Parameter(name = "id", description = "品牌ID", required = true)
    @ApiResponse(responseCode = "200", description = "更新成功",
            content = @Content(schema = @Schema(implementation = CommonResult.class)))
    @ApiResponse(responseCode = "500", description = "更新失败")
    @RequestMapping(value = "/update/{id}", method = RequestMethod.POST)
    @ResponseBody
    public CommonResult update(@PathVariable("id") Long id,
                               @Validated @RequestBody PmsBrandParam pmsBrandParam) {
        CommonResult commonResult;
        int count = brandService.updateBrand(id, pmsBrandParam);
        if (count == 1) {
            commonResult = CommonResult.success(count);
        } else {
            commonResult = CommonResult.failed();
        }
        return commonResult;
    }

    @Operation(summary =  "删除品牌")
    @Parameter(name = "id", description = "品牌ID", required = true)
    @ApiResponse(responseCode = "200", description = "删除成功",
            content = @Content(schema = @Schema(implementation = CommonResult.class)))
    @ApiResponse(responseCode = "500", description = "删除失败")
    @RequestMapping(value = "/delete/{id}", method = RequestMethod.GET)
    @ResponseBody
    public CommonResult delete(@PathVariable("id") Long id) {
        int count = brandService.deleteBrand(id);
        if (count == 1) {
            return CommonResult.success(null);
        } else {
            return CommonResult.failed();
        }
    }

    @Operation(summary =  "根据品牌名称分页获取品牌列表")
    @RequestMapping(value = "/list", method = RequestMethod.GET)
    @ResponseBody
    public CommonResult<CommonPage<PmsBrand>> getList(@Parameter(description = "品牌名称关键字") @RequestParam(value = "keyword", required = false) String keyword,
                                                      @Parameter(description = "显示状态：0->不显示；1->显示") @RequestParam(value = "showStatus",required = false) Integer showStatus,
                                                      @Parameter(description = "页码", schema = @Schema(defaultValue = "1")) @RequestParam(value = "pageNum", defaultValue = "1") Integer pageNum,
                                                      @Parameter(description = "每页数量", schema = @Schema(defaultValue = "5")) @RequestParam(value = "pageSize", defaultValue = "5") Integer pageSize) {
        List<PmsBrand> brandList = brandService.listBrand(keyword,showStatus,pageNum, pageSize);
        return CommonResult.success(CommonPage.restPage(brandList));
    }

    @Operation(summary =  "根据编号查询品牌信息")
    @RequestMapping(value = "/{id}", method = RequestMethod.GET)
    @ResponseBody
    public CommonResult<PmsBrand> getItem(@Parameter(name = "id", description = "品牌ID", required = true) @PathVariable("id") Long id) {
        return CommonResult.success(brandService.getBrand(id));
    }

    @Operation(summary =  "批量删除品牌")
    @RequestMapping(value = "/delete/batch", method = RequestMethod.POST)
    @ResponseBody
    public CommonResult deleteBatch(@Parameter(name = "ids", description = "品牌ID列表", required = true) @RequestParam("ids") List<Long> ids) {
        int count = brandService.deleteBrand(ids);
        if (count > 0) {
            return CommonResult.success(count);
        } else {
            return CommonResult.failed();
        }
    }

    @Operation(summary =  "批量更新显示状态")
    @RequestMapping(value = "/update/showStatus", method = RequestMethod.POST)
    @ResponseBody
    public CommonResult updateShowStatus(@Parameter(name = "ids", description = "品牌ID列表", required = true) @RequestParam("ids") List<Long> ids,
                                   @Parameter(name = "showStatus", description = "显示状态：0->不显示；1->显示", required = true) @RequestParam("showStatus") Integer showStatus) {
        int count = brandService.updateShowStatus(ids, showStatus);
        if (count > 0) {
            return CommonResult.success(count);
        } else {
            return CommonResult.failed();
        }
    }

    @Operation(summary =  "批量更新厂家制造商状态")
    @RequestMapping(value = "/update/factoryStatus", method = RequestMethod.POST)
    @ResponseBody
    public CommonResult updateFactoryStatus(@Parameter(name = "ids", description = "品牌ID列表", required = true) @RequestParam("ids") List<Long> ids,
                                      @Parameter(name = "factoryStatus", description = "厂家制造商状态：0->没有该品牌；1->有该品牌", required = true) @RequestParam("factoryStatus") Integer factoryStatus) {
        int count = brandService.updateFactoryStatus(ids, factoryStatus);
        if (count > 0) {
            return CommonResult.success(count);
        } else {
            return CommonResult.failed();
        }
    }
}
