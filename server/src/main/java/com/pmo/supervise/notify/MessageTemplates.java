package com.pmo.supervise.notify;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 通知文案模板（TECHNICAL §4.6）：标题与正文集中一处，站内与钉钉共用，
 * 避免两个渠道出现两种说法。
 */
public final class MessageTemplates {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int MENTION_EXCERPT_CODE_POINTS = 100;

    private MessageTemplates() {
    }

    public static String dueSoonTitle(String taskTitle) {
        return "任务即将到期：" + taskTitle;
    }

    public static String dueSoonBody(String taskTitle, LocalDate dueDate) {
        return "任务「" + taskTitle + "」将于 " + DATE.format(dueDate) + " 到期，请及时提交验收。";
    }

    public static String overdueTitle(String taskTitle) {
        return "任务已逾期：" + taskTitle;
    }

    public static String overdueBody(String taskTitle, LocalDate dueDate, long overdueDays) {
        return "任务「" + taskTitle + "」截止 " + DATE.format(dueDate) + "，已逾期 " + overdueDays + " 天，请尽快处理。";
    }

    public static String urgeTitle(String taskTitle) {
        return "任务催办：" + taskTitle;
    }

    public static String urgeBody(String actorName, String taskTitle, LocalDate dueDate, long urgeCount) {
        return actorName + " 催办了任务「" + taskTitle + "」（截止 " + DATE.format(dueDate)
                + "，累计 " + urgeCount + " 次），请尽快提交验收。";
    }

    public static String rejectedBody(String actorName, String taskTitle, String reason) {
        return actorName + " 打回了任务「" + taskTitle + "」的验收：\n\n> " + reason + "\n\n请修改后重新提交。";
    }

    public static String reassignedBody(String taskTitle, String fromName, String toName, String reason) {
        return "任务「" + taskTitle + "」负责人由 " + fromName + " 变更为 " + toName + "。\n\n> " + reason;
    }

    public static String rescheduledBody(String taskTitle, LocalDate from, LocalDate to, String reason) {
        return "任务「" + taskTitle + "」截止日期由 " + DATE.format(from) + " 调整为 " + DATE.format(to)
                + "。\n\n> " + reason;
    }

    /** 被@标题：{作者} 在任务《{标题}》中@了你。 */
    public static String mentionTitle(String actorName, String taskTitle) {
        return actorName + " 在任务《" + taskTitle + "》中@了你";
    }

    /**
     * 被@正文：进度内容截到 100 个 code point 再补省略号。
     * 按 code point 截断才不会把 emoji 的代理对切坏。
     */
    public static String mentionBody(String content) {
        String value = content == null ? "" : content.trim();
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints <= MENTION_EXCERPT_CODE_POINTS) {
            return value;
        }
        int end = value.offsetByCodePoints(0, MENTION_EXCERPT_CODE_POINTS);
        return value.substring(0, end) + "…";
    }
}
