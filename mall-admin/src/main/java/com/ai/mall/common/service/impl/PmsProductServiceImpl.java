package com.ai.mall.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.ai.mall.common.service.RedisService;
import com.ai.mall.dao.*;
import com.ai.mall.dto.PmsProductParam;
import com.ai.mall.dto.PmsProductQueryParam;
import com.ai.mall.dto.PmsProductResult;
import com.ai.mall.mapper.*;
import com.ai.mall.model.*;
import com.ai.mall.service.PmsProductService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;
import com.ai.mall.common.lock.DistributedLock;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 商品管理Service实现类（集成Redis缓存 + MyBatis Plus）
 * Created by macro on 2018/4/26.
 */
@Service
public class PmsProductServiceImpl implements PmsProductService {
    private static final Logger LOGGER = LoggerFactory.getLogger(PmsProductServiceImpl.class);

    @Autowired
    private PmsProductMapper productMapper;
    @Autowired
    private RedisService redisService;
    @Autowired
    private PmsMemberPriceDao memberPriceDao;
    @Autowired
    private PmsMemberPriceMapper memberPriceMapper;
    @Autowired
    private PmsProductLadderDao productLadderDao;
    @Autowired
    private PmsProductLadderMapper productLadderMapper;
    @Autowired
    private PmsProductFullReductionDao productFullReductionDao;
    @Autowired
    private PmsProductFullReductionMapper productFullReductionMapper;
    @Autowired
    private PmsSkuStockDao skuStockDao;
    @Autowired
    private PmsSkuStockMapper skuStockMapper;
    @Autowired
    private PmsProductAttributeValueDao productAttributeValueDao;
    @Autowired
    private PmsProductAttributeValueMapper productAttributeValueMapper;
    @Autowired
    private CmsSubjectProductRelationDao subjectProductRelationDao;
    @Autowired
    private CmsSubjectProductRelationMapper subjectProductRelationMapper;
    @Autowired
    private CmsPrefrenceAreaProductRelationDao prefrenceAreaProductRelationDao;
    @Autowired
    private CmsPrefrenceAreaProductRelationMapper prefrenceAreaProductRelationMapper;
    @Autowired
    private PmsProductDao productDao;
    @Autowired
    private PmsProductVertifyRecordDao productVertifyRecordDao;

    @Value("${redis.database}")
    private String REDIS_DATABASE;
    @Value("${redis.key.product}")
    private String REDIS_KEY_PRODUCT;
    @Value("${redis.key.productList}")
    private String REDIS_KEY_PRODUCT_LIST;
    @Value("${redis.expire.product}")
    private Long REDIS_PRODUCT_EXPIRE;

    @Autowired
    private DistributedLock distributedLock;

    private static final String CACHE_NULL_MARK = "__NULL__";
    private static final long NULL_MARK_EXPIRE_SECONDS = 60;
    private static final long EXPIRE_JITTER_SECONDS = 300;

    @Override
    public int create(PmsProductParam productParam) {
        int count;
        //创建商品
        PmsProduct product = productParam;
        product.setId(null);
        productMapper.insertSelective(product);
        //根据促销类型设置价格：会员价格、阶梯价格、满减价格
        Long productId = product.getId();
        //会员价格
        relateAndInsertList(memberPriceDao, productParam.getMemberPriceList(), productId);
        //阶梯价格
        relateAndInsertList(productLadderDao, productParam.getProductLadderList(), productId);
        //满减价格
        relateAndInsertList(productFullReductionDao, productParam.getProductFullReductionList(), productId);
        //处理sku的编码
        handleSkuStockCode(productParam.getSkuStockList(), productId);
        //添加sku库存信息
        relateAndInsertList(skuStockDao, productParam.getSkuStockList(), productId);
        //添加商品参数,添加自定义商品规格
        relateAndInsertList(productAttributeValueDao, productParam.getProductAttributeValueList(), productId);
        //关联专题
        relateAndInsertList(subjectProductRelationDao, productParam.getSubjectProductRelationList(), productId);
        //关联优选
        relateAndInsertList(prefrenceAreaProductRelationDao, productParam.getPrefrenceAreaProductRelationList(), productId);
        count = 1;
        return count;
    }

    @Override
    @SuppressWarnings("unchecked")
    public PmsProduct getProduct(Long id) {
        String cacheKey = REDIS_DATABASE + ":" + REDIS_KEY_PRODUCT + ":" + id;
        Object cached = redisService.get(cacheKey);
        if (cached instanceof PmsProduct) {
            LOGGER.debug("命中Redis缓存: {}", cacheKey);
            return (PmsProduct) cached;
        }
        // 防穿透：命中空标记说明 DB 也无此数据，直接返回，避免反复打 DB
        if (CACHE_NULL_MARK.equals(cached)) {
            LOGGER.debug("命中空标记，跳过 DB 查询: {}", cacheKey);
            return null;
        }
        // 防击穿：热点 key 失效时仅一个线程回源，其余等待后重读缓存
        String lockKey = "product:" + id;
        String requestId = distributedLock.tryLock(lockKey, 10, 200);
        if (requestId == null) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Object retry = redisService.get(cacheKey);
            if (retry instanceof PmsProduct) {
                return (PmsProduct) retry;
            }
            return null;
        }
        try {
            PmsProduct product = productMapper.selectById(id);
            if (product != null) {
                // 防雪崩：基础过期时间叠加随机抖动，避免大量 key 同时失效
                long expire = REDIS_PRODUCT_EXPIRE + ThreadLocalRandom.current().nextLong(EXPIRE_JITTER_SECONDS + 1);
                redisService.set(cacheKey, product, expire);
            } else {
                // 防穿透：查不到也缓存空标记，短 TTL
                redisService.set(cacheKey, CACHE_NULL_MARK, NULL_MARK_EXPIRE_SECONDS);
            }
            return product;
        } finally {
            distributedLock.unlock(lockKey, requestId);
        }
    }

    @Override
    public Page<PmsProduct> listByPage(PmsProductQueryParam productQueryParam, Integer pageNum, Integer pageSize) {
        LambdaQueryWrapper<PmsProduct> wrapper = buildProductQueryWrapper(productQueryParam);
        Page<PmsProduct> page = new Page<>(pageNum, pageSize);
        return productMapper.selectPage(page, wrapper);
    }

    private void handleSkuStockCode(List<PmsSkuStock> skuStockList, Long productId) {
        if (CollectionUtils.isEmpty(skuStockList)) return;
        for (int i = 0; i < skuStockList.size(); i++) {
            PmsSkuStock skuStock = skuStockList.get(i);
            if (StrUtil.isEmpty(skuStock.getSkuCode())) {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd");
                StringBuilder sb = new StringBuilder();
                //日期
                sb.append(sdf.format(new Date()));
                //四位商品id
                sb.append(String.format("%04d", productId));
                //三位索引id
                sb.append(String.format("%03d", i + 1));
                skuStock.setSkuCode(sb.toString());
            }
        }
    }

    @Override
    public PmsProductResult getUpdateInfo(Long id) {
        return productDao.getUpdateInfo(id);
    }

    @Override
    public int update(Long id, PmsProductParam productParam) {
        int count;
        //更新商品信息
        PmsProduct product = productParam;
        product.setId(id);
        productMapper.updateByPrimaryKeySelective(product);
        //清除商品缓存
        clearProductCache(id);
        //会员价格
        PmsMemberPriceExample pmsMemberPriceExample = new PmsMemberPriceExample();
        pmsMemberPriceExample.createCriteria().andProductIdEqualTo(id);
        memberPriceMapper.deleteByExample(pmsMemberPriceExample);
        relateAndInsertList(memberPriceDao, productParam.getMemberPriceList(), id);
        //阶梯价格
        PmsProductLadderExample ladderExample = new PmsProductLadderExample();
        ladderExample.createCriteria().andProductIdEqualTo(id);
        productLadderMapper.deleteByExample(ladderExample);
        relateAndInsertList(productLadderDao, productParam.getProductLadderList(), id);
        //满减价格
        PmsProductFullReductionExample fullReductionExample = new PmsProductFullReductionExample();
        fullReductionExample.createCriteria().andProductIdEqualTo(id);
        productFullReductionMapper.deleteByExample(fullReductionExample);
        relateAndInsertList(productFullReductionDao, productParam.getProductFullReductionList(), id);
        //修改sku库存信息
        handleUpdateSkuStockList(id, productParam);
        //修改商品参数,添加自定义商品规格
        PmsProductAttributeValueExample productAttributeValueExample = new PmsProductAttributeValueExample();
        productAttributeValueExample.createCriteria().andProductIdEqualTo(id);
        productAttributeValueMapper.deleteByExample(productAttributeValueExample);
        relateAndInsertList(productAttributeValueDao, productParam.getProductAttributeValueList(), id);
        //关联专题
        CmsSubjectProductRelationExample subjectProductRelationExample = new CmsSubjectProductRelationExample();
        subjectProductRelationExample.createCriteria().andProductIdEqualTo(id);
        subjectProductRelationMapper.deleteByExample(subjectProductRelationExample);
        relateAndInsertList(subjectProductRelationDao, productParam.getSubjectProductRelationList(), id);
        //关联优选
        CmsPrefrenceAreaProductRelationExample prefrenceAreaExample = new CmsPrefrenceAreaProductRelationExample();
        prefrenceAreaExample.createCriteria().andProductIdEqualTo(id);
        prefrenceAreaProductRelationMapper.deleteByExample(prefrenceAreaExample);
        relateAndInsertList(prefrenceAreaProductRelationDao, productParam.getPrefrenceAreaProductRelationList(), id);
        count = 1;
        return count;
    }

    private void handleUpdateSkuStockList(Long id, PmsProductParam productParam) {
        //当前的sku信息
        List<PmsSkuStock> currSkuList = productParam.getSkuStockList();
        //当前没有sku直接删除
        if (CollUtil.isEmpty(currSkuList)) {
            PmsSkuStockExample skuStockExample = new PmsSkuStockExample();
            skuStockExample.createCriteria().andProductIdEqualTo(id);
            skuStockMapper.deleteByExample(skuStockExample);
            return;
        }
        //获取初始sku信息
        PmsSkuStockExample skuStockExample = new PmsSkuStockExample();
        skuStockExample.createCriteria().andProductIdEqualTo(id);
        List<PmsSkuStock> oriStuList = skuStockMapper.selectByExample(skuStockExample);
        //获取新增sku信息
        List<PmsSkuStock> insertSkuList = currSkuList.stream().filter(item -> item.getId() == null).collect(Collectors.toList());
        //获取需要更新的sku信息
        List<PmsSkuStock> updateSkuList = currSkuList.stream().filter(item -> item.getId() != null).collect(Collectors.toList());
        List<Long> updateSkuIds = updateSkuList.stream().map(PmsSkuStock::getId).collect(Collectors.toList());
        //获取需要删除的sku信息
        List<PmsSkuStock> removeSkuList = oriStuList.stream().filter(item -> !updateSkuIds.contains(item.getId())).collect(Collectors.toList());
        handleSkuStockCode(insertSkuList, id);
        handleSkuStockCode(updateSkuList, id);
        //新增sku
        if (CollUtil.isNotEmpty(insertSkuList)) {
            relateAndInsertList(skuStockDao, insertSkuList, id);
        }
        //删除sku
        if (CollUtil.isNotEmpty(removeSkuList)) {
            List<Long> removeSkuIds = removeSkuList.stream().map(PmsSkuStock::getId).collect(Collectors.toList());
            PmsSkuStockExample removeExample = new PmsSkuStockExample();
            removeExample.createCriteria().andIdIn(removeSkuIds);
            skuStockMapper.deleteByExample(removeExample);
        }
        //修改sku
        if (CollUtil.isNotEmpty(updateSkuList)) {
            for (PmsSkuStock pmsSkuStock : updateSkuList) {
                skuStockMapper.updateByPrimaryKeySelective(pmsSkuStock);
            }
        }
    }

    @Override
    public List<PmsProduct> list(PmsProductQueryParam productQueryParam, Integer pageSize, Integer pageNum) {
        LambdaQueryWrapper<PmsProduct> wrapper = buildProductQueryWrapper(productQueryParam);
        Page<PmsProduct> page = new Page<>(pageNum, pageSize);
        return productMapper.selectPage(page, wrapper).getRecords();
    }

    @Override
    public int updateVerifyStatus(List<Long> ids, Integer verifyStatus, String detail) {
        PmsProduct product = new PmsProduct();
        product.setVerifyStatus(verifyStatus);
        LambdaQueryWrapper<PmsProduct> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(PmsProduct::getId, ids);
        int count = productMapper.update(product, wrapper);
        clearProductCacheList(ids);
        //修改完审核状态后插入审核记录
        List<PmsProductVertifyRecord> list = new ArrayList<>();
        for (Long id : ids) {
            PmsProductVertifyRecord record = new PmsProductVertifyRecord();
            record.setProductId(id);
            record.setCreateTime(new Date());
            record.setDetail(detail);
            record.setStatus(verifyStatus);
            record.setVertifyMan("test");
            list.add(record);
        }
        productVertifyRecordDao.insertList(list);
        return count;
    }

    @Override
    public int updatePublishStatus(List<Long> ids, Integer publishStatus) {
        PmsProduct record = new PmsProduct();
        record.setPublishStatus(publishStatus);
        LambdaQueryWrapper<PmsProduct> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(PmsProduct::getId, ids);
        clearProductCacheList(ids);
        return productMapper.update(record, wrapper);
    }

    @Override
    public int updateRecommendStatus(List<Long> ids, Integer recommendStatus) {
        PmsProduct record = new PmsProduct();
        record.setRecommandStatus(recommendStatus);
        LambdaQueryWrapper<PmsProduct> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(PmsProduct::getId, ids);
        clearProductCacheList(ids);
        return productMapper.update(record, wrapper);
    }

    @Override
    public int updateNewStatus(List<Long> ids, Integer newStatus) {
        PmsProduct record = new PmsProduct();
        record.setNewStatus(newStatus);
        LambdaQueryWrapper<PmsProduct> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(PmsProduct::getId, ids);
        clearProductCacheList(ids);
        return productMapper.update(record, wrapper);
    }

    @Override
    public int updateDeleteStatus(List<Long> ids, Integer deleteStatus) {
        PmsProduct record = new PmsProduct();
        record.setDeleteStatus(deleteStatus);
        LambdaQueryWrapper<PmsProduct> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(PmsProduct::getId, ids);
        clearProductCacheList(ids);
        return productMapper.update(record, wrapper);
    }

    @Override
    public List<PmsProduct> list(String keyword) {
        LambdaQueryWrapper<PmsProduct> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PmsProduct::getDeleteStatus, 0);
        if (StringUtils.isNotBlank(keyword)) {
            wrapper.and(w -> w.like(PmsProduct::getName, keyword)
                    .or()
                    .like(PmsProduct::getProductSn, keyword));
        }
        return productMapper.selectList(wrapper);
    }

    private LambdaQueryWrapper<PmsProduct> buildProductQueryWrapper(PmsProductQueryParam param) {
        LambdaQueryWrapper<PmsProduct> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PmsProduct::getDeleteStatus, 0)
                .eq(param.getPublishStatus() != null, PmsProduct::getPublishStatus, param.getPublishStatus())
                .eq(param.getVerifyStatus() != null, PmsProduct::getVerifyStatus, param.getVerifyStatus())
                .like(StringUtils.isNotBlank(param.getKeyword()), PmsProduct::getName, param.getKeyword())
                .eq(StringUtils.isNotBlank(param.getProductSn()), PmsProduct::getProductSn, param.getProductSn())
                .eq(param.getBrandId() != null, PmsProduct::getBrandId, param.getBrandId())
                .eq(param.getProductCategoryId() != null, PmsProduct::getProductCategoryId, param.getProductCategoryId())
                .orderByDesc(PmsProduct::getCreateTime);
        return wrapper;
    }

    private void clearProductCache(Long productId) {
        String key = REDIS_DATABASE + ":" + REDIS_KEY_PRODUCT + ":" + productId;
        redisService.del(key);
        redisService.del(REDIS_DATABASE + ":" + REDIS_KEY_PRODUCT_LIST);
    }

    private void clearProductCacheList(List<Long> productIds) {
        for (Long id : productIds) {
            String key = REDIS_DATABASE + ":" + REDIS_KEY_PRODUCT + ":" + id;
            redisService.del(key);
        }
        redisService.del(REDIS_DATABASE + ":" + REDIS_KEY_PRODUCT_LIST);
    }

    /**
     * 建立和插入关系表操作
     *
     * @param dao       可以操作的dao
     * @param dataList  要插入的数据
     * @param productId 建立关系的id
     */
    private void relateAndInsertList(Object dao, List dataList, Long productId) {
        try {
            if (CollectionUtils.isEmpty(dataList)) return;
            for (Object item : dataList) {
                Method setId = item.getClass().getMethod("setId", Long.class);
                setId.invoke(item, (Long) null);
                Method setProductId = item.getClass().getMethod("setProductId", Long.class);
                setProductId.invoke(item, productId);
            }
            Method insertList = dao.getClass().getMethod("insertList", List.class);
            insertList.invoke(dao, dataList);
        } catch (Exception e) {
            LOGGER.warn("创建产品出错:{}", e.getMessage());
            throw new RuntimeException(e.getMessage());
        }
    }

}
