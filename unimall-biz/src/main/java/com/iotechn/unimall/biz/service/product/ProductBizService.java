package com.iotechn.unimall.biz.service.product;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.iotechn.unimall.biz.executor.GlobalExecutor;
import com.iotechn.unimall.biz.service.category.CategoryBizService;
import com.iotechn.unimall.core.exception.AppServiceException;
import com.iotechn.unimall.core.exception.ExceptionDefinition;
import com.iotechn.unimall.core.exception.ServiceException;
import com.iotechn.unimall.data.component.CacheComponent;
import com.iotechn.unimall.data.constant.CacheConst;
import com.iotechn.unimall.data.domain.SpuDO;
import com.iotechn.unimall.data.dto.goods.SkuDTO;
import com.iotechn.unimall.data.dto.goods.SpuDTO;
import com.iotechn.unimall.data.mapper.SkuMapper;
import com.iotechn.unimall.data.mapper.SpuMapper;
import com.iotechn.unimall.data.model.Page;
import com.iotechn.unimall.data.model.SearchWrapperModel;
import com.iotechn.unimall.data.search.SearchEngine;
import com.iotechn.unimall.data.search.exception.SearchEngineException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Created by rize on 2019/7/12.
 */
@Service
public class ProductBizService {

    @Autowired
    private SpuMapper spuMapper;

    @Autowired
    private SkuMapper skuMapper;

    @Autowired
    private CategoryBizService categoryBizService;

    @Autowired
    private CacheComponent cacheComponent;

    @Autowired(required = false)
    private SearchEngine searchEngine;


    /**
     * SPU ?????????detail??????????????????????????????????????????
     */
    public static final String[] SPU_EXCLUDE_DETAIL_FIELDS;

    private static final Logger logger = LoggerFactory.getLogger(ProductBizService.class);

    static {
        Field[] fields = SpuDO.class.getDeclaredFields();
        List<String> tempList = new ArrayList<>();
        for (int i = 0; i < fields.length; i++) {
            Field field = fields[i];
            TableField annotation = field.getAnnotation(TableField.class);
            String name;
            if (annotation != null) {
                name = annotation.value();
            } else {
                name = field.getName();
            }
            if (!name.equals("detail")) {
                tempList.add(name);
            }
        }
        SPU_EXCLUDE_DETAIL_FIELDS = tempList.toArray(new String[0]);
    }

    /**
     * ?????????????????????????????????????????????????????????????????????????????????detail(????????????)??????
     *
     * @param pageNo
     * @param pageSize
     * @param categoryId
     * @param orderBy
     * @param isAsc
     * @param title
     * @return
     * @throws ServiceException
     */
    public Page<SpuDO> getProductPage(Integer pageNo, Integer pageSize, Long categoryId, String orderBy, Boolean isAsc, String title) throws ServiceException {
        if (!StringUtils.isEmpty(title)) {
            try {
                if (this.searchEngine != null) {
                    SearchWrapperModel searchWrapper =
                            new SearchWrapperModel()
                                    .div(pageNo, pageSize)
                                    .like("title", title);
                    if (categoryId != null && categoryId > 0) {
                        searchWrapper.eq("category_id", categoryId);
                    }
                    if (orderBy != null && isAsc != null) {
                        if (isAsc)
                            searchWrapper.orderByAsc(orderBy);
                        else
                            searchWrapper.orderByDesc(orderBy);
                    }
                    Page<SpuDO> searchRes = searchEngine.search(searchWrapper, SpuDO.class);
                    return searchRes;
                }
            } catch (SearchEngineException e) {
                logger.error("[??????????????????] ???????????? ??????", e);
                throw new AppServiceException(ExceptionDefinition.buildVariableException(ExceptionDefinition.SEARCH_ENGINE_INNER_EXCEPTION, e.getMessage()));
            }
            // ??????DB??????
            return this.getProductPageFromDB(pageNo, pageSize, categoryId, orderBy, isAsc, title);
        }
        // 1. ??????????????????????????????Id
        String zsetBucketKey;
        if ("price".equals(orderBy)) {
            zsetBucketKey = CacheConst.PRT_CATEGORY_ORDER_PRICE_ZSET + categoryId;
        } else if ("id".equals(orderBy)) {
            zsetBucketKey = CacheConst.PRT_CATEGORY_ORDER_ID_ZSET + categoryId;
        } else if ("sales".equals(orderBy)) {
            zsetBucketKey = CacheConst.PRT_CATEGORY_ORDER_SALES_ZSET + categoryId;
        } else {
            throw new AppServiceException(ExceptionDefinition.GOODS_ORDER_BY_WAY_ILLEGAL);
        }
        Page<String> page = cacheComponent.getZSetPage(zsetBucketKey, pageNo, pageSize, isAsc);
        if (page.getTotal() == 0) {
            // ??????????????????????????????DB??????
            List<SpuDO> productIdsFromDB = getProductIdsOnSaleFromDB(categoryId);
            // ????????????categoryId???????????????Category(???????????????) ???????????????Id????????????????????????????????????CategoryId???null???????????????????????????
            if (!CollectionUtils.isEmpty(productIdsFromDB)) {
                // ???????????????????????????ZSet???
                Set<ZSetOperations.TypedTuple<String>> set = productIdsFromDB.stream().map(item -> (ZSetOperations.TypedTuple<String>) (new DefaultTypedTuple("P" + item.getId(), item.getSales().doubleValue()))).collect(Collectors.toSet());
                // ????????????
                cacheComponent.putZSetMulti(zsetBucketKey, set);
                // ????????????????????????
                page = cacheComponent.getZSetPage(zsetBucketKey, pageNo, pageSize, isAsc);
            }
        }
        // ???Spu Hash????????????????????????????????????Id????????????Spu
        List<SpuDO> spuList = cacheComponent.getHashMultiAsList(CacheConst.PRT_SPU_HASH_BUCKET, page.getItems(), SpuDO.class);
        boolean hasEmptyObj = false;
        for (int i = 0; i < spuList.size(); i++) {
            SpuDO spuDO = spuList.get(i);
            if (spuDO == null) {
                // ??????????????????
                SpuDO spuDOFromDB = spuMapper.selectOne(new QueryWrapper<SpuDO>().select(SPU_EXCLUDE_DETAIL_FIELDS).eq("id", Long.parseLong(page.getItems().get(i).replace("P", ""))));
                if (spuDOFromDB == null) {
                    // ???????????????????????????
                    hasEmptyObj = true;
                    logger.error("[????????????????????????] key=" + zsetBucketKey + ";item=" + page.getItems().get(i));
                    cacheComponent.delZSet(zsetBucketKey, page.getItems().get(i));
                } else {
                    // ?????? spuList ??????
                    spuList.set(i, spuDOFromDB);
                    // ??????ClassifyIds
                    List<Long> familyCategoryIds = categoryBizService.getCategoryFamily(spuDOFromDB.getCategoryId());
                    SpuDTO spuDTO = new SpuDTO();
                    BeanUtils.copyProperties(spuDOFromDB, spuDTO);
                    spuDTO.setCategoryIds(familyCategoryIds);
                    cacheComponent.putHashObj(CacheConst.PRT_SPU_HASH_BUCKET, "P" + spuDOFromDB.getId(), spuDTO);
                }
            }
        }
        if (hasEmptyObj) {
            spuList = spuList.stream().filter(item -> item != null).collect(Collectors.toList());
        }
        return page.replace(spuList);
    }

    /**
     * ????????????????????????????????????????????????detail(????????????)??????
     *
     * @param pageNo
     * @param pageSize
     * @param categoryId
     * @param orderBy
     * @param isAsc
     * @param title
     * @return
     */
    public Page<SpuDO> getProductPageFromDB(Integer pageNo, Integer pageSize, Long categoryId, String orderBy, Boolean isAsc, String title) throws ServiceException {
        QueryWrapper<SpuDO> wrapper = new QueryWrapper<SpuDO>();
        wrapper.select(SPU_EXCLUDE_DETAIL_FIELDS);
        if (orderBy != null && isAsc != null) {
            if (isAsc) {
                wrapper.orderByAsc(orderBy);
            } else {
                wrapper.orderByDesc(orderBy);
            }
        }
        if (categoryId != null) {
            wrapper.eq("category_id", categoryId);
        }
        if (!StringUtils.isEmpty(title)) {
            wrapper.like("title", title);
        }
        return spuMapper.selectPage(Page.div(pageNo, pageSize, SpuDO.class), wrapper);
    }

    /**
     * ???????????????????????????Id????????? ????????????
     *
     * @param categoryId
     * @return
     */
    private List<SpuDO> getProductIdsOnSaleFromDB(Long categoryId) throws ServiceException {
        if (categoryId != null) {
            List<Long> categoryFamily = categoryBizService.getCategorySelfAndChildren(categoryId);
            return spuMapper.selectList(new QueryWrapper<SpuDO>().select("id", "sales").in("category_id", categoryFamily));
        } else {
            return spuMapper.selectList(new QueryWrapper<SpuDO>().select("id", "sales"));
        }
    }

    /**
     * ?????????????????????SKU????????????
     *
     * @param skuIds
     * @return
     */
    public List<SkuDTO> getSkuListByIds(List<Long> skuIds) {
        return skuMapper.getSkuDTOListByIds(skuIds);
    }

    /**
     * ?????????????????????SPU,???????????????detail??????
     *
     * @param id
     * @return
     */
    public SpuDO getProductByIdFromDB(Long id) {
        return spuMapper.selectOne(new QueryWrapper<SpuDO>().select(SPU_EXCLUDE_DETAIL_FIELDS).eq("id", id));
    }

    public SpuDO getProductByIdFromDBForUpdate(Long id) {
        return spuMapper.selectOne(new QueryWrapper<SpuDO>().select(SPU_EXCLUDE_DETAIL_FIELDS).eq("id", id).last(" FOR UPDATE"));
    }

    /**
     * ??????????????????SPU?????????detail??????
     *
     * @param spuId
     * @return
     */
    public SpuDTO getProductByIdFromCache(Long spuId) throws ServiceException {
        SpuDTO spuDTO = cacheComponent.getHashObj(CacheConst.PRT_SPU_HASH_BUCKET, "P" + spuId, SpuDTO.class);
        if (spuDTO == null) {
            SpuDO spuDO = spuMapper.selectOne(new QueryWrapper<SpuDO>().select(ProductBizService.SPU_EXCLUDE_DETAIL_FIELDS).eq("id", spuId));
            if (spuDO != null) {
                spuDTO = new SpuDTO();
                BeanUtils.copyProperties(spuDO, spuDTO);
                List<Long> categoryFamily = categoryBizService.getCategoryFamily(spuDO.getCategoryId());
                spuDTO.setCategoryIds(categoryFamily);
                cacheComponent.putHashObj(CacheConst.PRT_SPU_HASH_BUCKET, "P" + spuId, spuDTO);
            } else {
                throw new AppServiceException(ExceptionDefinition.GOODS_NOT_EXIST);
            }
        }
        return spuDTO;
    }

    /**
     * ????????????
     *
     * @param skuStockMap
     */
    public void decSkuStock(Map<Long, Integer> skuStockMap) {
        skuStockMap.forEach((k, v) -> skuMapper.decSkuStock(k, v));
    }

    @Autowired
    private TransactionTemplate transactionTemplate;

    /**
     * ??????????????????
     *
     * @param skuSalesMap
     */
    public void incSpuSales(Map<Long, Integer> skuSalesMap) {
        skuSalesMap.forEach((k, v) -> {
            // 1. ???????????????
            spuMapper.incSales(k, v);
            // 2. ????????????
            SpuDTO spuDtoFromCache = cacheComponent.getHashObj(CacheConst.PRT_SPU_HASH_BUCKET, "P" + k, SpuDTO.class);
            int isTheSame = -1;
            Double nullSource = cacheComponent.incZSetSource(CacheConst.PRT_CATEGORY_ORDER_SALES_ZSET + null, "P" + k, v);
            if (nullSource != null) {
                isTheSame = (int) Math.round(nullSource);
            }
            for (Long categoryId : spuDtoFromCache.getCategoryIds()) {
                Double source = cacheComponent.incZSetSource(CacheConst.PRT_CATEGORY_ORDER_SALES_ZSET + categoryId, "P" + k, v);
                if (source != null) {
                    int i = (int) Math.round(source);
                    if (i != isTheSame) {
                        // ????????????
                        isTheSame = - 1;
                    }
                }
            }
            if (isTheSame == -1) {
                // ??????????????????????????????????????? ??????????????????????????????????????????
                transactionTemplate.execute(new TransactionCallbackWithoutResult() {
                    @Override
                    protected void doInTransactionWithoutResult(TransactionStatus transactionStatus) {
                        // ?????????ID????????????????????????
                        SpuDO newSpuDO = ProductBizService.this.getProductByIdFromDBForUpdate(k);
                        List<Long> categoryFamily = categoryBizService.getCategoryFamily(newSpuDO.getCategoryId());
                        SpuDTO newSpuDto = new SpuDTO();
                        BeanUtils.copyProperties(newSpuDO, newSpuDto);
                        newSpuDto.setCategoryIds(categoryFamily);
                        cacheComponent.putHashObj(CacheConst.PRT_SPU_HASH_BUCKET, "P" + k, newSpuDto);
                        for (Long categoryId : categoryFamily) {
                            cacheComponent.putZSet(CacheConst.PRT_CATEGORY_ORDER_SALES_ZSET + categoryId, newSpuDO.getSales(), "P" + newSpuDO.getId());
                        }
                        cacheComponent.putZSet(CacheConst.PRT_CATEGORY_ORDER_SALES_ZSET + null, newSpuDO.getSales(), "P" + newSpuDO.getId());
                    }
                });
            } else {
                spuDtoFromCache.setSales(isTheSame);
                cacheComponent.putHashObj(CacheConst.PRT_SPU_HASH_BUCKET, "P" + k, spuDtoFromCache);
            }
            // 3. ??????????????????
            final SpuDTO finalSpuDto = spuDtoFromCache;
            GlobalExecutor.execute(() -> {
                if (searchEngine != null) {
                    SpuDO newSpuDO = new SpuDO();
                    BeanUtils.copyProperties(finalSpuDto, newSpuDO);
                    searchEngine.dataTransmission(newSpuDO);
                }
            });
        });
    }

}
