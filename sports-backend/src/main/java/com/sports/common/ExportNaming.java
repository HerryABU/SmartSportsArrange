package com.sports.common;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 导出文件命名统一（U11 / B13）：在文件名中带上「阶段 + 版本 + 生成时间」，
 * 便于区分原始/二次编排后、import/export、json/xlsx 等多版本产物，避免拿错版本。
 */
public final class ExportNaming {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * 兜底版本号（H2 修复：原 "2.5.0" 与当前 v2.6.8 冲突，导致 IDE 内运行 / 未配 manifest 时
     * 所有导出文件名带 _v2.5.0，与 README 标题对不上）。
     * 必须与 pom.xml 的 <version> 保持同步；正常打包时会被 jar 清单的 Implementation-Version 覆盖。
     */
    private static final String FALLBACK_VERSION = "2.6.8";

    private ExportNaming() {}

    /** 应用版本：优先取打包清单 Implementation-Version，回退到 FALLBACK_VERSION（须与 pom 同步） */
    public static String appVersion() {
        String v = ExportNaming.class.getPackage().getImplementationVersion();
        return (v != null && !v.isBlank()) ? v : FALLBACK_VERSION;
    }

    /** 生成时间戳 yyyyMMdd-HHmmss */
    public static String stamp() {
        return LocalDateTime.now().format(STAMP);
    }

    /** 阶段标识：是否二次编排后 */
    public static String stage(boolean afterSecondArrange) {
        return afterSecondArrange ? "二次编排后" : "初编排";
    }

    /** 在文件名主体（扩展名前）追加 _v<版本> */
    public static String withVersion(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return fileName + "_v" + appVersion();
        return fileName.substring(0, dot) + "_v" + appVersion() + fileName.substring(dot);
    }
}
