package com.pmo.supervise.source;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 颜色真源（TECHNICAL §3.1 / §8.2、DESIGN §1.2）：{@link SourcePalette} 是运行期唯一真源，
 * {@code web/src/styles/tokens.css} 只作设计参考。本测试同时钉住 8 组字面值与两处一致性，
 * 任何一边改了颜色都会红，避免「后端下发一套色、静态令牌另一套色」。
 */
class SourcePaletteTest {

    /** 8 组三元组按色板顺序钉死（名称/主色/浅底/边框）。 */
    private static final List<SourcePalette.Entry> PINNED = List.of(
            new SourcePalette.Entry("#1B4D8F", "#EAF1FA", "#C7D9EF", "墨蓝"),
            new SourcePalette.Entry("#3538CD", "#EEF4FF", "#C7D7FE", "靛蓝"),
            new SourcePalette.Entry("#0B7A6E", "#F0FDFA", "#99F6E4", "青色"),
            new SourcePalette.Entry("#067647", "#ECFDF3", "#ABEFC6", "绿色"),
            new SourcePalette.Entry("#B54708", "#FFFAEB", "#FEDF89", "琥珀"),
            new SourcePalette.Entry("#6941C6", "#F4F3FF", "#D9D6FE", "紫色"),
            new SourcePalette.Entry("#475467", "#F2F4F7", "#E4E7EC", "石板灰"),
            new SourcePalette.Entry("#3F6212", "#F3F6E8", "#C9D6A4", "橄榄"));

    @Test
    void entriesArePinnedToEightTriples() {
        assertThat(SourcePalette.entries()).containsExactlyElementsOf(PINNED);
        assertThat(SourcePalette.entries()).hasSize(8);
        assertThat(SourcePalette.entries().stream().map(SourcePalette.Entry::color).distinct()).hasSize(8);
        assertThat(SourcePalette.entries().stream().map(SourcePalette.Entry::name).distinct()).hasSize(8);
    }

    @Test
    void ofMapsEveryPinnedColorAndNormalizesInput() {
        for (SourcePalette.Entry entry : SourcePalette.entries()) {
            assertThat(SourcePalette.of(entry.color()))
                    .isEqualTo(new SourcePalette.Triple(entry.color(), entry.soft(), entry.border()));
            // 大小写 / 前后空白 / 省略 # 都应命中同一组
            assertThat(SourcePalette.of(" " + entry.color().toLowerCase(Locale.ROOT) + " "))
                    .isEqualTo(new SourcePalette.Triple(entry.color(), entry.soft(), entry.border()));
            assertThat(SourcePalette.of(entry.color().substring(1)))
                    .isEqualTo(new SourcePalette.Triple(entry.color(), entry.soft(), entry.border()));
            assertThat(SourcePalette.isKnown(entry.color())).isTrue();
        }
    }

    @Test
    void unknownColorFallsBackToSlateGrey() {
        assertThat(SourcePalette.of("#123456")).isEqualTo(SourcePalette.FALLBACK);
        assertThat(SourcePalette.of(null)).isEqualTo(SourcePalette.FALLBACK);
        assertThat(SourcePalette.of("  ")).isEqualTo(SourcePalette.FALLBACK);
        assertThat(SourcePalette.isKnown("#123456")).isFalse();
        assertThat(SourcePalette.isKnown(null)).isFalse();
        assertThat(SourcePalette.FALLBACK)
                .isEqualTo(new SourcePalette.Triple("#475467", "#F2F4F7", "#E4E7EC"));
    }

    @Test
    void tokensCssDeclaresExactlyTheSameEightTriples() throws IOException {
        Path css = resolveTokensCss();
        String content = Files.readString(css);

        Pattern pattern = Pattern.compile(
                "--src-([a-z]+)-(text|soft|border)\\s*:\\s*(#[0-9a-fA-F]{6})\\s*;");
        Matcher matcher = pattern.matcher(content);
        Map<String, String[]> byPrefix = new LinkedHashMap<>();
        while (matcher.find()) {
            byPrefix.computeIfAbsent(matcher.group(1), key -> new String[3]);
            int index = switch (matcher.group(2)) {
                case "text" -> 0;
                case "soft" -> 1;
                default -> 2;
            };
            byPrefix.get(matcher.group(1))[index] = matcher.group(3).toLowerCase(Locale.ROOT);
        }

        assertThat(byPrefix)
                .as("tokens.css 应声明 8 组 --src-<name>-text/soft/border（实际 %s）", byPrefix.keySet())
                .hasSize(8);

        Map<String, SourcePalette.Triple> declared = new LinkedHashMap<>();
        byPrefix.forEach((prefix, triple) -> {
            assertThat(triple).as("--src-%s-* 三元组不完整", prefix).doesNotContainNull();
            declared.put(triple[0], new SourcePalette.Triple(triple[0], triple[1], triple[2]));
        });

        Map<String, SourcePalette.Triple> palette = new LinkedHashMap<>();
        for (SourcePalette.Entry entry : SourcePalette.entries()) {
            palette.put(entry.color().toLowerCase(Locale.ROOT),
                    new SourcePalette.Triple(
                            entry.color().toLowerCase(Locale.ROOT),
                            entry.soft().toLowerCase(Locale.ROOT),
                            entry.border().toLowerCase(Locale.ROOT)));
        }

        assertThat(declared).isEqualTo(palette);
    }

    /** Maven 从 server/ 运行；兼容从仓库根运行的情况。 */
    private static Path resolveTokensCss() {
        Path fromModule = Path.of("..", "web", "src", "styles", "tokens.css");
        if (Files.exists(fromModule)) {
            return fromModule;
        }
        Path fromRoot = Path.of("web", "src", "styles", "tokens.css");
        assertThat(Files.exists(fromRoot))
                .as("找不到 web/src/styles/tokens.css（相对 server/ 或仓库根）")
                .isTrue();
        return fromRoot;
    }
}
