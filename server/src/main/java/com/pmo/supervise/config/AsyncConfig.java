package com.pmo.supervise.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 钉钉推送异步线程池（TECHNICAL §4.6）：core 2 / max 4 / queue 200。
 * 队列满时抛 TaskRejectedException，由发送侧把状态标 FAILED，绝不阻塞业务事务。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    public static final String DINGTALK_EXECUTOR = "dingtalkExecutor";

    @Bean(name = DINGTALK_EXECUTOR, destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor dingtalkExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("dingtalk-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        return executor;
    }
}
