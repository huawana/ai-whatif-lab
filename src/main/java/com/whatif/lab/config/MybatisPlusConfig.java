package com.whatif.lab.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** MyBatis-Plus 拦截器配置。 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 分页插件。
     *
     * <p>注意：MyBatis-Plus 3.5.9 起依赖 JSqlParser 的能力被拆到
     * {@code mybatis-plus-jsqlparser}，只引 starter 时这里会编译不过 ——
     * pom 里已经显式补上该依赖。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
