package com.pmo.supervise;

import com.pmo.supervise.config.DingTalkProperties;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 会议任务督办系统启动类。
 * 调度（扫描/报表）与异步（钉钉推送）两条主线在此显式开启，避免散落注解。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@EnableAsync
public class SuperviseApplication {

    public static void main(String[] args) {
        SpringApplication.run(SuperviseApplication.class, args);
    }

    /** 启动时只打印一次钉钉配置状态（缺失项只列名称，不输出任何值）。 */
    @Bean
    public ApplicationRunner dingTalkConfigurationReporter(DingTalkProperties properties) {
        return args -> properties.logStatusOnce();
    }
}
