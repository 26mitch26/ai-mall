package com.ai.mall.mall.search.manager;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.indices.CreateIndexResponse;
import co.elastic.clients.json.JsonData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringReader;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Elasticsearch管理器
 * 支持中文分词（ik_max_word）、同义词匹配搜索
 */
@Component
public class ElasticsearchManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ElasticsearchManager.class);

    @Autowired
    private ElasticsearchClient elasticsearchClient;

    /** 电商领域同义词映射 */
    private static final Map<String, String> SYNONYM_MAP = new LinkedHashMap<>();

    static {
        // 电商领域同义词配置
        SYNONYM_MAP.put("手机", "移动电话,智能机,智能手机");
        SYNONYM_MAP.put("电脑", "计算机,微机,PC");
        SYNONYM_MAP.put("笔记本", "笔记本电脑,laptop");
        SYNONYM_MAP.put("平板", "平板电脑,tablet");
        SYNONYM_MAP.put("耳机", "耳麦,耳塞");
        SYNONYM_MAP.put("充电器", "电源适配器,充电头");
        SYNONYM_MAP.put("冰箱", "电冰箱,制冷柜");
        SYNONYM_MAP.put("洗衣机", "洗衣设备,滚筒洗衣机");
        SYNONYM_MAP.put("电视", "电视机,液晶电视,智能电视");
        SYNONYM_MAP.put("空调", "冷气机,空气调节器");
        SYNONYM_MAP.put("路由器", "无线路由器,WiFi路由器");
        SYNONYM_MAP.put("键盘", "机械键盘,外接键盘");
        SYNONYM_MAP.put("鼠标", "光电鼠标,无线鼠标");
        SYNONYM_MAP.put("显示器", "监视器,屏幕,显示屏");
        SYNONYM_MAP.put("硬盘", "磁盘,存储盘,固态硬盘");
    }

    /**
     * 生成同义词配置字符串（Elasticsearch synonym filter格式）
     * 格式: 手机 => 移动电话,智能机
     */
    public String buildSynonymRules() {
        return SYNONYM_MAP.entrySet().stream()
                .map(entry -> entry.getKey() + " => " + entry.getValue())
                .collect(Collectors.joining("\n"));
    }

    /**
     * 创建支持中文分词和同义词的索引
     *
     * @param indexName 索引名称
     * @return 是否创建成功
     */
    public boolean createIndexWithChineseAnalyzer(String indexName) {
        try {
            String synonymRules = buildSynonymRules();
            LOGGER.info("创建索引[{}]，同义词规则:\n{}", indexName, synonymRules);

            String mappingsJson = """
                    {
                      "settings": {
                        "analysis": {
                          "analyzer": {
                            "ik_smart_synonym": {
                              "tokenizer": "ik_smart",
                              "filter": ["synonym_filter"]
                            },
                            "ik_max_word_synonym": {
                              "tokenizer": "ik_max_word",
                              "filter": ["synonym_filter"]
                            }
                          },
                          "filter": {
                            "synonym_filter": {
                              "type": "synonym",
                              "synonyms": [%s]
                            }
                          }
                        }
                      },
                      "mappings": {
                        "properties": {
                          "name": {
                            "type": "text",
                            "analyzer": "ik_max_word_synonym",
                            "search_analyzer": "ik_smart_synonym"
                          },
                          "subTitle": {
                            "type": "text",
                            "analyzer": "ik_max_word_synonym",
                            "search_analyzer": "ik_smart_synonym"
                          },
                          "keywords": {
                            "type": "text",
                            "analyzer": "ik_max_word_synonym",
                            "search_analyzer": "ik_smart_synonym"
                          },
                          "brandName": {
                            "type": "keyword"
                          },
                          "productCategoryName": {
                            "type": "keyword"
                          },
                          "price": {
                            "type": "double"
                          },
                          "sale": {
                            "type": "integer"
                          }
                        }
                      }
                    }
                    """.formatted(synonymRules.lines()
                    .map(rule -> "\"" + rule.replace("\"", "\\\"") + "\"")
                    .collect(Collectors.joining(",")));

            CreateIndexResponse response = elasticsearchClient.indices()
                    .create(create -> create
                            .index(indexName)
                            .withJson(new StringReader(mappingsJson))
                    );
            boolean acknowledged = response.acknowledged();
            LOGGER.info("创建索引[{}]结果: {}", indexName, acknowledged);
            return acknowledged;
        } catch (IOException e) {
            LOGGER.error("创建索引[{}]失败: {}", indexName, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 中文分词搜索（使用ik_max_word分词器）
     *
     * @param indexName 索引名称
     * @param keyword   搜索关键词
     * @param fields    搜索字段列表
     * @param from      起始位置
     * @param size      返回数量
     * @return 搜索结果列表
     */
    public List<Map<String, Object>> searchWithChineseAnalyzer(String indexName, String keyword,
                                                                List<String> fields, int from, int size) {
        try {
            LOGGER.info("中文分词搜索 - 索引:{}, 关键词:{}, 字段:{}", indexName, keyword, fields);

            SearchResponse<JsonData> response = elasticsearchClient.search(search -> search
                            .index(indexName)
                            .from(from)
                            .size(size)
                            .query(q -> q
                                    .multiMatch(mm -> mm
                                            .fields(fields)
                                            .query(keyword)
                                            .analyzer("ik_max_word")
                                    )
                            ),
                    JsonData.class
            );

            List<Map<String, Object>> results = new ArrayList<>();
            for (Hit<JsonData> hit : response.hits().hits()) {
                Map<String, Object> item = new HashMap<>();
                item.put("id", hit.id());
                item.put("score", hit.score());
                if (hit.source() != null) {
                    item.put("source", hit.source().toJson().asJsonObject());
                }
                results.add(item);
            }
            LOGGER.info("中文分词搜索完成，命中数: {}", response.hits().total().value());
            return results;
        } catch (IOException e) {
            LOGGER.error("中文分词搜索失败: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 同义词匹配搜索（使用synonym filter + ik分词器）
     * 搜索"手机"时也能匹配到"移动电话"、"智能机"等同义词
     *
     * @param indexName 索引名称
     * @param keyword   搜索关键词
     * @param fields    搜索字段列表
     * @param from      起始位置
     * @param size      返回数量
     * @return 搜索结果列表
     */
    public List<Map<String, Object>> searchWithSynonym(String indexName, String keyword,
                                                        List<String> fields, int from, int size) {
        try {
            LOGGER.info("同义词匹配搜索 - 索引:{}, 关键词:{}, 字段:{}", indexName, keyword, fields);

            // 构建同义词扩展查询：原始词 + 同义词
            List<String> expandedTerms = expandWithSynonyms(keyword);
            LOGGER.info("同义词扩展结果: {} → {}", keyword, expandedTerms);

            SearchResponse<JsonData> response = elasticsearchClient.search(search -> search
                            .index(indexName)
                            .from(from)
                            .size(size)
                            .query(q -> q
                                    .bool(b -> {
                                        // 使用同义词分词器进行multi_match搜索
                                        b.should(s -> s
                                                .multiMatch(mm -> mm
                                                        .fields(fields)
                                                        .query(keyword)
                                                        .analyzer("ik_smart_synonym")
                                                        .boost(2.0f)
                                        ));
                                        // 额外对每个同义词进行匹配，提高召回率
                                        for (String term : expandedTerms) {
                                            b.should(s -> s
                                                    .multiMatch(mm -> mm
                                                            .fields(fields)
                                                            .query(term)
                                                            .analyzer("ik_smart")
                                                            .boost(0.5f)
                                                    )
                                            );
                                        }
                                        b.minimumShouldMatch("1");
                                        return b;
                                    })
                            ),
                    JsonData.class
            );

            List<Map<String, Object>> results = new ArrayList<>();
            for (Hit<JsonData> hit : response.hits().hits()) {
                Map<String, Object> item = new HashMap<>();
                item.put("id", hit.id());
                item.put("score", hit.score());
                if (hit.source() != null) {
                    item.put("source", hit.source().toJson().asJsonObject());
                }
                results.add(item);
            }
            LOGGER.info("同义词匹配搜索完成，命中数: {}", response.hits().total().value());
            return results;
        } catch (IOException e) {
            LOGGER.error("同义词匹配搜索失败: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 使用同义词扩展关键词
     *
     * @param keyword 原始关键词
     * @return 包含同义词的扩展词列表
     */
    private List<String> expandWithSynonyms(String keyword) {
        List<String> expanded = new ArrayList<>();
        expanded.add(keyword);

        for (Map.Entry<String, String> entry : SYNONYM_MAP.entrySet()) {
            String key = entry.getKey();
            String synonyms = entry.getValue();
            // 关键词匹配同义词表中的主词
            if (key.equals(keyword)) {
                expanded.addAll(Arrays.asList(synonyms.split(",")));
            }
            // 关键词匹配同义词表中的某个同义词
            if (synonyms.contains(keyword)) {
                expanded.add(key);
                for (String syn : synonyms.split(",")) {
                    if (!syn.equals(keyword)) {
                        expanded.add(syn.trim());
                    }
                }
            }
        }
        return expanded.stream().distinct().collect(Collectors.toList());
    }

    /**
     * 添加自定义同义词
     *
     * @param keyWord  主词
     * @param synonyms 同义词（逗号分隔）
     */
    public void addSynonym(String keyWord, String synonyms) {
        SYNONYM_MAP.put(keyWord, synonyms);
        LOGGER.info("添加同义词: {} => {}", keyWord, synonyms);
    }

    /**
     * 获取所有同义词配置
     */
    public Map<String, String> getSynonymMap() {
        return Collections.unmodifiableMap(SYNONYM_MAP);
    }
}
