package com.pmo.supervise.common;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

/**
 * 全应用唯一的“今天/现在”来源（TECHNICAL §4.1）。
 * 每请求取一次 today 并作为参数下传，SQL 中不出现 CURDATE()——跨页一致的结构性保证。
 */
@Component
public class ShanghaiClock {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final Clock clock;

    public ShanghaiClock() {
        this(Clock.system(ZONE));
    }

    ShanghaiClock(Clock clock) {
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    public Clock clock() {
        return clock;
    }
}
