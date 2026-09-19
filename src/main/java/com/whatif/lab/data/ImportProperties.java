package com.whatif.lab.data;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 数据导入参数。 */
@ConfigurationProperties(prefix = "whatif.import")
public record ImportProperties(@DefaultValue("2000") int batchSize) {
}
