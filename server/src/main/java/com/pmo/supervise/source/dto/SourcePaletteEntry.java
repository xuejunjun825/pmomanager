package com.pmo.supervise.source.dto;

/** 色板条目（前端 SourcePaletteEntry）：设置页色板由后端提供，前端不硬编码 8 组色值。 */
public record SourcePaletteEntry(String color, String soft, String border, String name) {
}
