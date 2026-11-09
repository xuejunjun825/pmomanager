package com.pmo.supervise.notify;

import com.pmo.supervise.common.NotifyType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** dedup_key 四类规则（TECHNICAL §4.6）：同一业务事实永远得到同一个 key。 */
class NotificationKeysTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 9);

    @Test
    void dailyKeyIsTaskDateAndType() {
        assertThat(NotificationKeys.daily(12, DAY, NotifyType.OVERDUE))
                .isEqualTo("task:12:2026-11-09:OVERDUE");
        assertThat(NotificationKeys.daily(12, DAY, NotifyType.DUE_SOON))
                .isNotEqualTo(NotificationKeys.daily(12, DAY, NotifyType.OVERDUE));
    }

    @Test
    void urgeKeyIncludesActorAndSequence() {
        assertThat(NotificationKeys.urge(12, DAY, 7, 1)).isEqualTo("urge:12:2026-11-09:7:1");
        assertThat(NotificationKeys.urge(12, DAY, 7, 2))
                .isNotEqualTo(NotificationKeys.urge(12, DAY, 7, 1));
        assertThat(NotificationKeys.urge(12, DAY, 8, 1))
                .isNotEqualTo(NotificationKeys.urge(12, DAY, 7, 1));
    }

    @Test
    void eventKeyIsTaskEventAndType() {
        assertThat(NotificationKeys.event(12, 99, NotifyType.REASSIGNED))
                .isEqualTo("task:12:99:REASSIGNED");
    }

    @Test
    void reportKeyIsUserDateAndType() {
        assertThat(NotificationKeys.report(5, DAY, NotifyType.DAILY_REPORT))
                .isEqualTo("report:5:2026-11-09:DAILY_REPORT");
    }
}
