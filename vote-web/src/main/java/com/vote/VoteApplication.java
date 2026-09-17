package com.vote;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 实时投票排行榜与防刷系统启动类
 * @author hzp
 * @since 2026-9-13
 */
@SpringBootApplication(scanBasePackages = "com.vote")
@EnableScheduling
@MapperScan("com.vote.model.mapper")
public class VoteApplication {

    public static void main(String[] args) {
        SpringApplication.run(VoteApplication.class, args);
    }
}
