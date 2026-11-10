package com.pmo.supervise.source.dto;

/** 启停来源（与用户启停接口同形状，前端复用同一套交互）。 */
public record SourceTypeStatusRequest(boolean enabled) {
}
