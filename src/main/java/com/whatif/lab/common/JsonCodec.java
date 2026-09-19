package com.whatif.lab.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * JSON 编解码集中点。
 *
 * <p><b>为什么 JSON 列一律用 String 存、而不是 MyBatis-Plus 的 JacksonTypeHandler</b>：
 * 1) 可复现性：Scenario DSL / 模型系数 / 指标快照都是「复现契约」的一部分，
 *    存原文意味着落库的字节与算 hash 的字节完全一致。走了 typeHandler 的
 *    「对象 → 再序列化」中间态，JSON 键序、数字格式都可能被改写，hash 就对不上了。
 * 2) 可审计：出问题时直接 SELECT 那一列就能看到引擎当时吃进去的东西。
 * 代价是要显式 parse —— 全部收在本类里，不让 ObjectMapper 散落各处。
 */
@Component
public class JsonCodec {

    private final ObjectMapper mapper;

    public JsonCodec(ObjectMapper mapper) {
        // 不在未知字段上炸：LLM 多输出一个字段不应让整条链路失败
        this.mapper = mapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    }

    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败: " + e.getMessage(), e);
        }
    }

    public <T> T read(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 解析失败(" + type.getSimpleName() + "): " + e.getMessage(), e);
        }
    }

    /** 泛型读取（List<Change> 这类结构化入参需要）。 */
    public <T> T read(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 解析失败: " + e.getMessage(), e);
        }
    }

    public Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("JSON 解析失败(Map): " + e.getMessage(), e);
        }
    }

    public List<Double> readDoubleList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<List<Double>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("JSON 解析失败(List<Double>): " + e.getMessage(), e);
        }
    }

    public List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("JSON 解析失败(List<String>): " + e.getMessage(), e);
        }
    }

    public ObjectMapper raw() {
        return mapper;
    }
}
