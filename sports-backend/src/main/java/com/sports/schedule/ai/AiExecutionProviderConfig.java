package com.sports.schedule.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * AI 执行提供器配置——在容器启动时把 {@link OnnxSessionFactory} 装成进程级单例。
 *
 * <p>配置项（{@code application.yml} 的 {@code sports.schedule.ai.*}）：</p>
 * <pre>
 * sports:
 *   schedule:
 *     ai:
 *       execution-provider: cpu        # cpu | directml | cuda | auto
 *       intra-op-threads: 0            # 0 = 由 ORT 自行决定
 *       inter-op-threads: 0
 * </pre>
 *
 * <p><b>怎么选</b></p>
 * <ul>
 *   <li><b>Windows x64 + 想要 GPU</b>：打包时用 {@code -Ponnx-gpu-directml}，
 *       运行时设 {@code execution-provider=directml}（覆盖 NVIDIA/AMD/Intel，
 *       无需 CUDA/cuDNN，DirectX 12 系统自带）；</li>
 *   <li><b>Windows ARM64（骁龙等）</b>：<b>只能用 DirectML</b>——官方 EP 兼容表里
 *       DirectML 的 ARM 支持为 Yes，CUDA/TensorRT 均为 No，且 Qualcomm 一栏写明
 *       DirectML 是 Win on ARM 的 GPU 加速首选；</li>
 *   <li><b>Linux 服务器 + NVIDIA</b>：{@code -Ponnx-gpu-cuda} + {@code execution-provider=cuda}，
 *       机器上需自行装 CUDA 12 与 cuDNN 9；</li>
 *   <li><b>不确定</b>：{@code execution-provider=auto}，按 directml → cuda → cpu 探测，
 *       全部失败自动回落 CPU 并在 {@code /api/ai/status} 说明原因。</li>
 * </ul>
 *
 * <p><b>为什么默认 cpu</b>：GPU 依赖会让 jar 体积从 ~42MB 涨到 ~200MB（CUDA），
 * 且引入驱动/运行库版本耦合。默认 CPU 保证「换个机器就能跑」，要加速时再显式开启。</p>
 */
@Slf4j
@Configuration
public class AiExecutionProviderConfig {

    @Value("${sports.schedule.ai.execution-provider:cpu}")
    private String executionProvider;

    @Value("${sports.schedule.ai.intra-op-threads:0}")
    private int intraOpThreads;

    @Value("${sports.schedule.ai.inter-op-threads:0}")
    private int interOpThreads;

    @PostConstruct
    public void install() {
        OnnxSessionFactory factory =
                new OnnxSessionFactory(executionProvider, intraOpThreads, interOpThreads);
        OnnxSessionFactory.install(factory);
        log.info("AI 推理后端: OS={} arch={} 请求EP={}（GPU 加速需换依赖：DirectML 用 -Ponnx-gpu-directml，"
                        + "CUDA 用 -Ponnx-gpu-cuda）",
                System.getProperty("os.name"), System.getProperty("os.arch"), factory.requested());
    }
}
