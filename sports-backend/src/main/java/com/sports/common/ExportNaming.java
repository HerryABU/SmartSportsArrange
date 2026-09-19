package com.sports.common;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

/**
 * 导出文件命名统一（U11 / B13）：在文件名中带上「阶段 + 版本 + 生成时间」，
 * 便于区分原始/二次编排后、import/export、json/xlsx 等多版本产物，避免拿错版本。
 */
public final class ExportNaming {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * 终极兜底版本号（仅当既无 jar 清单、又无 pom.properties 时才命中，例如裸 classpath 直接跑 class）。
     * <p><b>不再作为主同步点</b>：打包后版本优先取 Maven 自动生成的
     * {@code META-INF/maven/com.sports/sports/pom.properties}（始终与 pom.xml 的 {@code <version>}
     * 一致，无需手动维护），其次取 jar 清单 Implementation-Version。本常量只是最后一道防线。</p>
     */
    private static final String FALLBACK_VERSION = "2.6.8";

    /** pom.properties 资源路径（Maven 打包时由 pom <version> 自动生成） */
    private static final String POM_PROPERTIES =
            "META-INF/maven/com.sports/sports/pom.properties";

    private ExportNaming() {}

    /**
     * 应用版本，按优先级：
     * 1) jar 清单 Implementation-Version（正常打包得到）；
     * 2) Maven 生成的 pom.properties 的 version（与 pom.xml 始终同步，消除手动同步遗漏）；
     * 3) FALLBACK_VERSION（仅裸 classpath 运行等极端场景）。
     */
    public static String appVersion() {
        String v = ExportNaming.class.getPackage().getImplementationVersion();
        if (v == null || v.isBlank()) {
            v = readPomVersion();
        }
        return (v != null && !v.isBlank()) ? v : FALLBACK_VERSION;
    }

    /** 读取 Maven 自动生成的 pom.properties 中的 version（与 pom.xml 同步，无需手动维护） */
    private static String readPomVersion() {
        try (InputStream in = ExportNaming.class.getClassLoader()
                .getResourceAsStream(POM_PROPERTIES)) {
            if (in == null) return null;
            Properties p = new Properties();
            p.load(in);
            return p.getProperty("version");
        } catch (Exception ignored) {
            return null;
        }
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
