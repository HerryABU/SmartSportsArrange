package com.sports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sports.common.util.ExportNaming;

/**
 * 帮助页（{@code -h / --help}）的契约测试。
 *
 * <p>钉住的是一条**很容易复发**的缺陷：帮助页里曾写死 {@code sports-2.7.3.jar}，
 * 而同一屏的横幅同时显示 {@code v2.8.5} —— 同一个程序在两处自报不同版本，
 * 用户照着帮助页敲的命令还找不到文件。根因是**版本号被抄了第二份**。
 * 所以这里的断言不是「等于某个版本」，而是「**必须与 {@link ExportNaming#appVersion()} 一致**」，
 * 以及「帮助页里不得再出现硬编码的 jar 名」。</p>
 */
class SportsApplicationHelpTest {

    private static List<String> lines() {
        return Arrays.asList(SportsApplication.helpLines());
    }

    @Test
    @DisplayName("横幅与用法行里的版本必须一致，且由 appVersion() 推导")
    void bannerAndUsageShareOneVersion() {
        String v = ExportNaming.appVersion();
        assertTrue(v != null && !v.isBlank(), "版本解析不能返回空");

        List<String> ls = lines();
        assertTrue(ls.stream().anyMatch(l -> l.contains("v" + v)),
                "横幅应包含 v" + v + "，实际：" + ls.subList(0, 3));
        assertTrue(ls.stream().anyMatch(l -> l.contains("java -jar sports-" + v + ".jar")),
                "用法行应包含 sports-" + v + ".jar（由版本推导，不得写死）");
    }

    @Test
    @DisplayName("帮助页里不得再出现任何硬编码的 sports-<版本>.jar")
    void noHardcodedJarName() {
        String v = ExportNaming.appVersion();
        for (String l : lines()) {
            int i = l.indexOf("sports-");
            while (i >= 0) {
                int end = l.indexOf(".jar", i);
                assertTrue(end > i, "sports- 后面应紧跟 <版本>.jar：" + l);
                assertEquals("sports-" + v + ".jar", l.substring(i, end + 4),
                        "帮助页里出现了与 appVersion() 不一致的 jar 名（版本被抄了第二份）：" + l);
                i = l.indexOf("sports-", end + 4);
            }
        }
    }

    @Test
    @DisplayName("帮助页覆盖三条命令（默认 / h2 / mysql）且提示 -h 只打印不启动")
    void coversThreeDatabaseModes() {
        String joined = String.join("\n", lines());
        assertTrue(joined.contains("--spring.profiles.active=h2"), "应给出 H2 用法");
        assertTrue(joined.contains("--spring.profiles.active=mysql"), "应给出 MySQL 用法");
        assertTrue(joined.contains("不会启动 Web 服务"), "应说明 -h 不会启动服务");
        assertFalse(joined.contains("sports-2.7."), "不得残留历史版本号");
    }
}
