package com.pmo.supervise.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * 钉钉扫码 state（TECHNICAL §4.8）：32 字节随机、存 session、5 分钟有效、单次消费。
 *
 * <p>state 与 next 一起入 session，回调时一次取出：伪造的 state 无法命中任何会话，
 * 已消费或过期的 state 也取不到第二次，因此重放无效。</p>
 */
@Component
public class DingTalkStateStore {

    static final String ATTR = "DINGTALK_OAUTH_STATE";
    static final Duration TTL = Duration.ofMinutes(5);

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public DingTalkStateStore() {
        this(Clock.systemUTC());
    }

    /** 单测可注入固定时钟，验证 5 分钟边界。 */
    DingTalkStateStore(Clock clock) {
        this.clock = clock;
    }

    /** 生成 state 并写入当前会话；同一会话只保留最近一次（旧 state 自动失效）。 */
    public String issue(HttpServletRequest request, String next) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        request.getSession(true).setAttribute(ATTR, new Pending(state, next, Instant.now(clock)));
        return state;
    }

    /** 消费 state：无论校验是否通过，会话里的 state 都会被移除（单次消费）。 */
    public Optional<Consumed> consume(HttpServletRequest request, String state) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return Optional.empty();
        }
        Object value = session.getAttribute(ATTR);
        session.removeAttribute(ATTR);
        if (!(value instanceof Pending pending) || state == null || state.isBlank()) {
            return Optional.empty();
        }
        if (!constantTimeEquals(pending.state(), state)) {
            return Optional.empty();
        }
        if (pending.issuedAt().plus(TTL).isBefore(Instant.now(clock))) {
            return Optional.empty();
        }
        return Optional.of(new Consumed(pending.next()));
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    /** 回调成功后需要带回前端的站内路径（可能为空）。 */
    public record Consumed(String next) {
    }

    private record Pending(String state, String next, Instant issuedAt) implements java.io.Serializable {
    }
}
