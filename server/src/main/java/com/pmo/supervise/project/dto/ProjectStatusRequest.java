package com.pmo.supervise.project.dto;

/** 启停项目（与来源/用户的启停接口同形状，前端复用同一套交互）。 */
public record ProjectStatusRequest(boolean enabled) {
}
