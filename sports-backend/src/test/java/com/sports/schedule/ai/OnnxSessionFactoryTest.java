package com.sports.schedule.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OnnxSessionFactory} 的行为约定。
 *
 * <p>本类不依赖模型文件、不依赖 GPU，纯验证「EP 选择与降级」这条策略链——
 * 它是「机器有显卡却没跑在显卡上」这类困惑的唯一防线，必须可测。</p>
 */
@DisplayName("ONNX 执行提供器工厂（GPU 加速 / 多架构）")
class OnnxSessionFactoryTest {

    @Test
    @DisplayName("默认 cpu：不注册任何 GPU EP，且不算降级")
    void cpuIsDefaultAndNotDegraded() {
        OnnxSessionFactory f = new OnnxSessionFactory(null, 0, 0);

        assertEquals(OnnxSessionFactory.EP_CPU, f.requested());
        assertNotNull(f.newSessionOptions(), "必须给出可用的 SessionOptions");
        assertEquals(OnnxSessionFactory.EP_CPU, f.effective());
        assertFalse(f.isGpu());
        assertNull(f.degradeReason(), "cpu 是主动选择，不算降级");
    }

    @Test
    @DisplayName("非法取值回落 cpu 而不是抛异常")
    void unknownProviderFallsBackToCpu() {
        OnnxSessionFactory f = new OnnxSessionFactory("OpenVINO-随便写的", 0, 0);

        assertEquals(OnnxSessionFactory.EP_CPU, f.requested());
        assertEquals(OnnxSessionFactory.EP_CPU, f.effective());
    }

    @Test
    @DisplayName("大小写不敏感：DIRECTML / DirectML / directml 等价")
    void providerIsCaseInsensitive() {
        assertEquals(OnnxSessionFactory.EP_DIRECTML, new OnnxSessionFactory("DIRECTML", 0, 0).requested());
        assertEquals(OnnxSessionFactory.EP_DIRECTML, new OnnxSessionFactory("DirectML", 0, 0).requested());
        assertEquals(OnnxSessionFactory.EP_CUDA, new OnnxSessionFactory("  CUDA  ", 0, 0).requested());
    }

    @Test
    @DisplayName("GPU EP 不可用时必须降级 CPU 而不是让编排失败")
    void gpuUnavailableDegradesToCpuQuietly() {
        // 单测环境是 CPU-only 的 CI 机器（且默认依赖是 CPU 版 ORT，DirectML/CUDA 类不存在），
        // 正好复现「用户设了 directml 但没换依赖」的真实场景。
        OnnxSessionFactory f = new OnnxSessionFactory(OnnxSessionFactory.EP_AUTO, 0, 0);

        assertNotNull(f.newSessionOptions(), "降级也必须给出可用 SessionOptions");
        assertEquals(OnnxSessionFactory.EP_CPU, f.effective(),
                "GPU 不可用时应回落 CPU：AI 编排不能因为没显卡就跑不了");
        assertNotNull(f.degradeReason(), "降级必须给出原因，供 /api/ai/status 如实展示");
        assertTrue(f.degradeReason().contains("CPU"), "降级原因应说明回落目标：" + f.degradeReason());
        assertFalse(f.probeLog().isEmpty(), "探测过程必须留痕，否则「为什么没跑上 GPU」无从排查");
    }

    @Test
    @DisplayName("线程数配置：0 表示交给 ORT 自适应，非 0 要能安全设置")
    void threadCountsAreAppliedOrIgnoredSafely() {
        assertNotNull(new OnnxSessionFactory(OnnxSessionFactory.EP_CPU, 0, 0).newSessionOptions());
        assertNotNull(new OnnxSessionFactory(OnnxSessionFactory.EP_CPU, 2, 1).newSessionOptions());
        // 负数属于配置错误，不应炸掉启动
        assertNotNull(new OnnxSessionFactory(OnnxSessionFactory.EP_CPU, -1, -1).newSessionOptions());
    }

    @Test
    @DisplayName("describe() 报出请求值/生效值/GPU 标记/OS 架构，供 /api/ai/status 观测")
    void describeExposesEffectiveBackend() {
        var d = new OnnxSessionFactory(OnnxSessionFactory.EP_CUDA, 0, 0).describe();

        assertEquals(OnnxSessionFactory.EP_CUDA, d.get("requested"));
        assertNotNull(d.get("effective"));
        assertNotNull(d.get("gpu"));
        assertNotNull(d.get("platform"), "必须报 OS 架构——ARM 机器排障的第一眼信息");
        assertNotNull(d.get("probe"));
    }

    @Test
    @DisplayName("进程级单例：未 install 时给 CPU 默认值，install 后生效")
    void singletonIsUsableBeforeInstall() {
        // 四个 AI 服务都走静态单例（其中 AdversarialSchemeService 不是 Spring 创建的），
        // 因此「未 install」这条路径必须是安全且可用的。
        OnnxSessionFactory f = OnnxSessionFactory.get();
        assertNotNull(f);
        assertNotNull(f.newSessionOptions());

        OnnxSessionFactory installed = new OnnxSessionFactory(OnnxSessionFactory.EP_CPU, 0, 0);
        OnnxSessionFactory.install(installed);
        try {
            assertEquals(installed, OnnxSessionFactory.get());
        } finally {
            OnnxSessionFactory.install(f);   // 还原，避免污染同 JVM 内的其它测试
        }
    }
}
