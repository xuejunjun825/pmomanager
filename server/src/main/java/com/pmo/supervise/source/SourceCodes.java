package com.pmo.supervise.source;

/**
 * 唯一保留的硬编码来源编码（TECHNICAL §4.1）。
 * 会议决议的「不可停用 / 不可删除 / 形态锁定」和一键转任务都需要一个稳定编码，
 * 集中在此，其它地方不再出现三类来源分支。
 */
public final class SourceCodes {

    public static final String MEETING = "MEETING";

    private SourceCodes() {
    }

    /** 内置会议来源被锁定：不可停用、不可删除、形态不可改。 */
    public static boolean isLocked(String code) {
        return MEETING.equals(code);
    }
}
