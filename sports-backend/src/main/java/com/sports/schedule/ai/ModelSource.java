package com.sports.schedule.ai;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * 模型来源解析——让 ONNX 模型既能<b>打进 jar</b>（默认），又能从外部目录热替换。
 *
 * <p>为什么要这层抽象：训练侧（{@code sports-ai/}）与部署侧是刻意解耦的，但<strong>交付</strong>
 * 上不能让运维再去手工摆一个 {@code ../sports-ai/models} 目录——单 exe/jar 分发才是目标形态。
 * 因此模型随构建同步进 {@code src/main/resources/models}，运行时走 classpath；
 * 同时也允许把 {@code model-dir} 指向磁盘目录以替换模型而不重新打包。</p>
 *
 * <p>ONNX Runtime 的 {@code createSession} 直接接受 {@code byte[]}，因此 jar 内资源
 * <b>无需落地成临时文件</b>即可加载——这避免了「jar 内资源必须解压到 temp」的常见坑
 * （临时文件权限、清理、并发覆盖）。</p>
 *
 * <h3>支持的写法</h3>
 * <ul>
 *   <li>{@code classpath:/models}（默认，jar 内）：从 classpath 读资源；</li>
 *   <li>{@code ./ai-models} 或 {@code file:/opt/ai-models}：从磁盘目录读，便于热替换。</li>
 * </ul>
 */
@Slf4j
public final class ModelSource {

    /** classpath 前缀 */
    public static final String CLASSPATH_PREFIX = "classpath:";
    /** 文件前缀 */
    public static final String FILE_PREFIX = "file:";

    private ModelSource() {
    }

    /**
     * 读取模型字节。
     *
     * @param modelDir 模型目录（{@code classpath:/models} / 普通路径 / {@code file:} 前缀）
     * @param name     模型文件名
     * @return 模型字节；找不到时 {@link Optional#empty()}（调用方据此优雅回退）
     */
    public static Optional<byte[]> read(String modelDir, String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String base = modelDir == null ? "" : modelDir.trim();
        try {
            if (base.startsWith(CLASSPATH_PREFIX)) {
                String loc = base.substring(CLASSPATH_PREFIX.length());
                if (!loc.startsWith("/")) {
                    loc = "/" + loc;
                }
                if (loc.endsWith("/")) {
                    loc = loc.substring(0, loc.length() - 1);
                }
                String path = loc + "/" + name;
                try (InputStream in = ModelSource.class.getResourceAsStream(path)) {
                    if (in == null) {
                        return Optional.empty();
                    }
                    return Optional.of(in.readAllBytes());
                }
            }
            String dir = base.startsWith(FILE_PREFIX) ? base.substring(FILE_PREFIX.length()) : base;
            Path path = Paths.get(dir, name);
            if (!Files.isRegularFile(path)) {
                return Optional.empty();
            }
            return Optional.of(Files.readAllBytes(path));
        } catch (IOException | RuntimeException ex) {
            log.warn("模型读取失败 [{} / {}]: {}", modelDir, name, ex.toString());
            return Optional.empty();
        }
    }

    /** 模型来源的可读描述（供 {@code /api/ai/status} 展示，让运维一眼看出跑的是 jar 内还是外部模型）。 */
    public static String describe(String modelDir, String name) {
        String base = modelDir == null ? "" : modelDir.trim();
        if (base.startsWith(CLASSPATH_PREFIX)) {
            String loc = base.substring(CLASSPATH_PREFIX.length());
            if (!loc.startsWith("/")) {
                loc = "/" + loc;
            }
            if (loc.endsWith("/")) {
                loc = loc.substring(0, loc.length() - 1);
            }
            boolean present = ModelSource.class.getResource(loc + "/" + name) != null;
            return "jar 内 " + loc + "/" + name + (present ? "" : "（缺失）");
        }
        String dir = base.startsWith(FILE_PREFIX) ? base.substring(FILE_PREFIX.length()) : base;
        Path path = Paths.get(dir, name);
        return "外部文件 " + path.toAbsolutePath() + (Files.isRegularFile(path) ? "" : "（缺失）");
    }

    /** 该模型当前是否可读。 */
    public static boolean exists(String modelDir, String name) {
        return read(modelDir, name).isPresent();
    }
}
