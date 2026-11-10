package com.pmo.supervise.notify;

import com.pmo.supervise.common.NotifyType;

import java.time.LocalDate;

/**
 * dedup_key 生成规则（TECHNICAL §4.6）。四类规则集中在此，测试直接钉住字符串形状。
 */
public final class NotificationKeys {

    private NotificationKeys() {
    }

    /** task:{taskId}:{notifyDate}:{type} —— T-1 预警与逾期每日提醒。 */
    public static String daily(long taskId, LocalDate notifyDate, NotifyType type) {
        return "task:" + taskId + ":" + notifyDate + ":" + type.name();
    }

    /** urge:{taskId}:{notifyDate}:{actorId}:{n} —— 催办（n 为当日同人第几次）。 */
    public static String urge(long taskId, LocalDate notifyDate, long actorId, long sequence) {
        return "urge:" + taskId + ":" + notifyDate + ":" + actorId + ":" + sequence;
    }

    /** task:{taskId}:{eventId}:{type} —— 打回 / 改派 / 改期。 */
    public static String event(long taskId, long eventId, NotifyType type) {
        return "task:" + taskId + ":" + eventId + ":" + type.name();
    }

    /** report:{userId}:{notifyDate}:{type} —— 日报 / 周报。 */
    public static String report(long userId, LocalDate notifyDate, NotifyType type) {
        return "report:" + userId + ":" + notifyDate + ":" + type.name();
    }

    /** mention:{progressId}:{userId} —— 被@通知按(进度,用户)幂等（AC-10）。 */
    public static String mention(long progressId, long userId) {
        return "mention:" + progressId + ":" + userId;
    }
}
