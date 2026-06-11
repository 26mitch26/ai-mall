package com.ai.mall.demo.service;

import com.ai.mall.demo.dto.PmsBrandDto;
import com.ai.mall.model.PmsBrand;

import java.util.List;

/**
 * DemoService接口
 * Created by macro on 2019/4/8.
 */
public interface DemoService {
    List<PmsBrand> listAllBrand();

    int createBrand(PmsBrandDto pmsBrandDto);

    int updateBrand(Long id, PmsBrandDto pmsBrandDto);

    int deleteBrand(Long id);

    List<PmsBrand> listBrand(int pageNum, int pageSize);

    PmsBrand getBrand(Long id);
}
