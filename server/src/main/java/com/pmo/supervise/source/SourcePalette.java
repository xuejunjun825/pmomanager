package com.pmo.supervise.source;

import java.util.List;
import java.util.Locale;

/**
 * 来源色板（TECHNICAL §3.1 / DESIGN §1.2）：颜色的唯一真源。
 *
 * <p>运行期标签颜色一律由后端按 hex 派生 (主色, 浅底, 边框) 三元组下发，
 * 前端不再按来源编码分支写 CSS；{@code tokens.css} 里的静态色值只是设计参考，
 * 由 SourcePaletteTest 钉住两者字面一致。
 */
public final class SourcePalette {

    /** 主色 / 浅底 / 边框。 */
    public record Triple(String color, String soft, String border) {
    }

    /** 色板条目（hex 主色 + 派生的浅底、边框 + 中文名）。 */
    public record Entry(String color, String soft, String border, String name) {
    }

    /** 未知或缺失颜色的兜底：石板灰，保证页面不会出现空白标签。 */
    public static final Triple FALLBACK = new Triple("#475467", "#F2F4F7", "#E4E7EC");

    private static final List<Entry> ENTRIES = List.of(
            new Entry("#1B4D8F", "#EAF1FA", "#C7D9EF", "墨蓝"),
            new Entry("#3538CD", "#EEF4FF", "#C7D7FE", "靛蓝"),
            new Entry("#0B7A6E", "#F0FDFA", "#99F6E4", "青色"),
            new Entry("#067647", "#ECFDF3", "#ABEFC6", "绿色"),
            new Entry("#B54708", "#FFFAEB", "#FEDF89", "琥珀"),
            new Entry("#6941C6", "#F4F3FF", "#D9D6FE", "紫色"),
            new Entry("#475467", "#F2F4F7", "#E4E7EC", "石板灰"),
            new Entry("#3F6212", "#F3F6E8", "#C9D6A4", "橄榄"));

    private SourcePalette() {
    }

    public static List<Entry> entries() {
        return ENTRIES;
    }

    /** hex → 三元组；未知或空值回退石板灰，绝不返回 null。 */
    public static Triple of(String hex) {
        if (hex == null || hex.isBlank()) {
            return FALLBACK;
        }
        String normalized = normalize(hex);
        for (Entry entry : ENTRIES) {
            if (entry.color().equalsIgnoreCase(normalized)) {
                return new Triple(entry.color(), entry.soft(), entry.border());
            }
        }
        return FALLBACK;
    }

    /** 配置写入时的颜色校验：只接受色板内的 8 个 hex。 */
    public static boolean isKnown(String hex) {
        if (hex == null || hex.isBlank()) {
            return false;
        }
        String normalized = normalize(hex);
        return ENTRIES.stream().anyMatch(entry -> entry.color().equalsIgnoreCase(normalized));
    }

    public static String normalize(String hex) {
        String trimmed = hex.trim();
        return trimmed.startsWith("#") ? trimmed.toUpperCase(Locale.ROOT)
                : ("#" + trimmed).toUpperCase(Locale.ROOT);
    }
}
