package com.whatif.lab.data.extract;

import java.util.Map;

/** 抽取出来的原始实体。 */
public record RawEntity(String entityType, String externalId, Map<String, Object> attributes) {
}
