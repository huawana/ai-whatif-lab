package com.whatif.lab.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 缓存 / 锁 / SingleFlight 参数。 */
@ConfigurationProperties(prefix = "whatif.cache")
public record CacheProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("86400") long resultTtlSeconds,
        @DefaultValue("800") long rebuildWaitMillis,
        @DefaultValue("120") long lockTtlSeconds) {
}
