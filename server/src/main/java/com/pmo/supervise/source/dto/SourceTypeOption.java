package com.pmo.supervise.source.dto;

import com.pmo.supervise.source.SourceForm;

/**
 * 下拉 / 筛选用的来源选项（meta.sourceTypes）：值、标签与三色同源，
 * 停用来源仍在列表里——历史任务还要能按它筛选（AC-5）。
 */
public record SourceTypeOption(
        String value,
        String label,
        String color,
        String softColor,
        String borderColor,
        SourceForm form,
        boolean enabled,
        int sort) {
}
