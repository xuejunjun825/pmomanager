package com.pmo.supervise.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.List;

/**
 * 人维度闭环矩阵（GET /api/matrix，AC-15）。
 * rangeFrom / rangeTo 为按截止日期筛选的生效区间，range=ALL 时两者为 null。
 * 全局 Jackson 配置为 non_null，这里显式保留 null：前端 MatrixView 契约里两个字段总是存在，
 * 「全部期间」与「接口少返回字段」必须能区分。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record MatrixView(
        LocalDate today,
        LocalDate rangeFrom,
        LocalDate rangeTo,
        List<MatrixRow> rows,
        MatrixRow totals) {
}
