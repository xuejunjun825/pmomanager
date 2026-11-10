package com.pmo.supervise.source.dto;

import com.pmo.supervise.source.SourceForm;

/**
 * 来源配置行（前端 SourceTypeRow）：表格与编辑弹窗共用一份，
 * 颜色三元组与锁定标记都由服务端算好，前端不再二次拼装。
 */
public record SourceTypeView(
        String code,
        String name,
        SourceForm form,
        String color,
        String softColor,
        String borderColor,
        int sort,
        boolean enabled,
        boolean builtin,
        boolean locked,
        long usageCount) {
}
