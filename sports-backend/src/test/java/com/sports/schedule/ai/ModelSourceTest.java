package com.sports.schedule.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型来源解析测试：模型必须**能随 jar 交付**（classpath），同时保留外部目录热替换能力。
 *
 * <p>这类测试的价值在于把「交付形态」变成可断言的事实：过去模型放在
 * {@code ../sports-ai/models}（相对运行目录的外部路径），打成 jar 分发后路径必然失效，
 * 而失效表现是**静默回退规则编排**——功能看似正常、AI 其实没跑。断言 classpath 可读，
 * 就把「模型有没有真的打进去」从人工检查变成了 CI 可见的红灯。</p>
 */
class ModelSourceTest {

    /** ONNX 是 protobuf 序列化：首个字段为 ir_version(varint)，tag 字节固定 0x08。 */
    private static boolean looksLikeOnnx(byte[] bytes) {
        return bytes != null && bytes.length > 8 && bytes[0] == 0x08;
    }

    @Test
    @DisplayName("classpath 模式：模型可从 classpath 读取（等价于 jar 内读取）")
    void readsFromClasspath() {
        Optional<byte[]> selector = ModelSource.read("classpath:/models", "algorithm_selector.onnx");
        assertTrue(selector.isPresent(), "algorithm_selector.onnx 应随构建同步进 resources/models");
        assertTrue(looksLikeOnnx(selector.get()), "读到的应是合法 ONNX（protobuf）内容");
        assertTrue(selector.get().length > 1000, "模型体量不应小到可疑");

        assertTrue(ModelSource.read("classpath:/models", "conflict_gnn.onnx").isPresent());
        assertTrue(ModelSource.read("classpath:/models", "scheme_generator.onnx").isPresent());
        assertTrue(ModelSource.read("classpath:/models", "scheme_discriminator.onnx").isPresent());
        assertTrue(ModelSource.read("classpath:/models", "scheme_refiner.onnx").isPresent());
        assertTrue(ModelSource.read("classpath:/models", "forecast_mimo.onnx").isPresent());
    }

    @Test
    @DisplayName("classpath 前缀写法容错：带不带前导/尾随斜杠等价")
    void classpathPrefixTolerance() {
        assertTrue(ModelSource.read("classpath:/models", "algorithm_selector.onnx").isPresent());
        assertTrue(ModelSource.read("classpath:/models/", "algorithm_selector.onnx").isPresent());
        assertTrue(ModelSource.read("classpath:models", "algorithm_selector.onnx").isPresent());
    }

    @Test
    @DisplayName("模型缺失：返回空而非异常（调用方据此优雅回退规则编排）")
    void missingReturnsEmpty() {
        assertTrue(ModelSource.read("classpath:/models", "not_exist.onnx").isEmpty());
        assertTrue(ModelSource.read("classpath:/no-such-dir", "algorithm_selector.onnx").isEmpty());
        assertTrue(ModelSource.read("/definitely/not/a/dir", "x.onnx").isEmpty());
        assertTrue(ModelSource.read("classpath:/models", null).isEmpty());
        assertTrue(ModelSource.read("classpath:/models", "  ").isEmpty());
        assertFalse(ModelSource.exists("classpath:/models", "not_exist.onnx"));
    }

    @Test
    @DisplayName("describe：给出可读来源（jar 内 / 外部文件）并标注缺失")
    void describeShowsSourceAndPresence() {
        String inJar = ModelSource.describe("classpath:/models", "algorithm_selector.onnx");
        assertTrue(inJar.contains("jar 内"), "应标明来自 jar 内：" + inJar);
        assertFalse(inJar.contains("缺失"), "已存在的模型不应标注缺失：" + inJar);

        String missing = ModelSource.describe("classpath:/models", "not_exist.onnx");
        assertTrue(missing.contains("缺失"), "缺失模型应标注：" + missing);

        String external = ModelSource.describe("./ai-models", "algorithm_selector.onnx");
        assertTrue(external.contains("外部文件"), "外部目录应标明来源：" + external);
        assertTrue(external.contains("缺失"));
    }

    @Test
    @DisplayName("file: 前缀与普通路径等价（外部热替换模型）")
    void filePrefixEqualsPlainPath() {
        // 指向一个必然不存在的目录：两种写法都应安全返回空
        assertEquals(ModelSource.read("file:/no/such/dir", "x.onnx").isPresent(),
                ModelSource.read("/no/such/dir", "x.onnx").isPresent());
        assertTrue(ModelSource.read("file:/no/such/dir", "x.onnx").isEmpty());
    }
}
