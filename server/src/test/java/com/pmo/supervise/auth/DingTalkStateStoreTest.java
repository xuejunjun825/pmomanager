package com.pmo.supervise.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * state 的三条硬约束（AC-18 / TECHNICAL §4.8）：5 分钟有效、单次消费、伪造不可通过。
 */
class DingTalkStateStoreTest {

    private static final Instant NOW = Instant.parse("2026-11-09T01:00:00Z");

    private final DingTalkStateStore store = new DingTalkStateStore(
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void issuedStateIsConsumedOnceWithNext() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String state = store.issue(request, "/my");

        assertThat(state).hasSizeGreaterThanOrEqualTo(40);
        assertThat(store.consume(request, state)).contains(new DingTalkStateStore.Consumed("/my"));
        // 第二次消费同一个 state：会话里已经取不到，重放无效
        assertThat(store.consume(request, state)).isEmpty();
    }

    @Test
    void forgedStateIsRejected() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        store.issue(request, null);

        assertThat(store.consume(request, "forged-state")).isEmpty();
        // 伪造尝试同样消耗掉会话里的 state，攻击者无法再拿合法 state 复用
        assertThat(request.getSession(false).getAttribute(DingTalkStateStore.ATTR)).isNull();
    }

    @Test
    void stateExpiresAfterFiveMinutes() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String state = store.issue(request, null);

        DingTalkStateStore later = new DingTalkStateStore(
                Clock.fixed(NOW.plus(Duration.ofMinutes(6)), ZoneOffset.UTC));
        assertThat(later.consume(request, state)).isEmpty();
    }

    @Test
    void stateIsStillValidJustBeforeFiveMinutes() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String state = store.issue(request, null);

        DingTalkStateStore before = new DingTalkStateStore(
                Clock.fixed(NOW.plus(Duration.ofMinutes(4)).plusSeconds(59), ZoneOffset.UTC));
        assertThat(before.consume(request, state)).isPresent();
    }

    @Test
    void missingSessionMeansNoState() {
        assertThat(store.consume(new MockHttpServletRequest(), "anything")).isEmpty();
    }
}
