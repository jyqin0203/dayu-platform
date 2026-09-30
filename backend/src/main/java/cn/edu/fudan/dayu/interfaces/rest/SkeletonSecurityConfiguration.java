package cn.edu.fudan.dayu.interfaces.rest;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * skeleton Profile 专用的最小安全配置。
 *
 * <p>它允许所有请求，仅用于空骨架启动验证；生产环境必须替换为真实的
 * Session、CSRF、身份认证和权限控制配置。</p>
 */
@Configuration
@Profile("skeleton")
class SkeletonSecurityConfiguration {

    /**
     * 构建 skeleton 环境的安全过滤链。
     */
    @Bean
    SecurityFilterChain skeletonSecurityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        return http.build();
    }
}
