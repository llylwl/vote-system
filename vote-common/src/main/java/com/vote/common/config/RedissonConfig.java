package com.vote.common.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 配置类
 * 单机模式，开启 Watchdog（默认 30 秒自动续期），防止锁提前释放
 * @author hzp
 * @since 2026-9-13
 */
@Configuration
public class RedissonConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

    @Value("${spring.data.redis.password:}")
    private String password;

    @Value("${spring.data.redis.database:0}")
    private int database;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        // 看门狗机制：默认 30 秒续期一次，防止业务未完成锁提前释放
        config.setLockWatchdogTimeout(30000);
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(database);
        if (password != null && !password.isEmpty()) {
            config.useSingleServer().setPassword(password);
        }
        return Redisson.create(config);
    }
}
