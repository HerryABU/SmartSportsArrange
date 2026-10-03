package com.sports.schedule.ai;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ONNX Runtime <b>Session 与执行提供器（EP）工厂</b>——GPU 加速与多架构支持的单一入口。
 *
 * <h3>为什么需要它</h3>
 * <p>原先四个 AI 服务各自 {@code new OrtSession.SessionOptions()}，拿到的是<b>默认纯 CPU</b>配置：
 * 机器有 N 卡 / 核显也一律走 CPU，且没有任何开关。Windows on ARM（骁龙等）上更彻底——
 * {@code com.microsoft:onnxruntime} 的 CPU jar 只内置 win-x64 / linux-x64 / linux-aarch64 /
 * osx-aarch64 四个平台的 native 库，<b>没有 win-arm64</b>，直接抛
 * {@code UnsatisfiedLinkError}。</p>
 *
 * <h3>EP 选择</h3>
 * <table border="1">
 *   <caption>各 EP 的适用范围（依据 ONNX Runtime 官方 EP 兼容表）</caption>
 *   <tr><th>取值</th><th>EP</th><th>覆盖硬件</th><th>Windows ARM64</th><th>额外依赖</th></tr>
 *   <tr><td>{@code cpu}</td><td>CPU</td><td>任意 x64/ARM64</td><td>见下方说明</td><td>无</td></tr>
 *   <tr><td>{@code directml}</td><td>DmlExecutionProvider</td><td>NVIDIA / AMD / Intel / Qualcomm</td>
 *       <td>✅ 官方 Full 支持</td><td>需 onnxruntime-directml 依赖 + DirectX 12</td></tr>
 *   <tr><td>{@code cuda}</td><td>CudaExecutionProvider</td><td>仅 NVIDIA 独显</td><td>❌ 不支持</td>
 *       <td>需 onnxruntime-gpu 依赖 + CUDA 12 + cuDNN 9</td></tr>
 *   <tr><td>{@code auto}</td><td>按 directml → cuda → cpu 依次探测</td><td>同上</td><td>✅</td><td>同上</td></tr>
 * </table>
 *
 * <p><b>Windows ARM64 的正解是 DirectML</b>：官方 EP 兼容表里 DirectML 的 ARM 支持为
 * {@code Yes}（CUDA / TensorRT 均为 {@code No}），且「Qualcomm (Windows on ARM)」一栏明确写着
 * DirectML 是该平台 GPU 加速的<b>首选</b>。因此：Windows ARM 机器请用
 * {@code onnxruntime-directml} 依赖 + {@code execution-provider=directml}。</p>
 *
 * <h3>降级原则</h3>
 * <p>本项目一贯「<b>失败即降级、绝不阻塞主流程</b>」：GPU EP 不可用（缺依赖、缺驱动、
 * 初始化抛错）时，<b>静默回落 CPU</b> 并在 {@link #describe()} 与日志里如实说明，
 * 绝不让编排失败。GPU 推理失败也不影响正确性——同一份 ONNX 图在 CPU 与 GPU 上
 * 只存在浮点末位差异。</p>
 */
@Slf4j
public final class OnnxSessionFactory {

    /** EP 取值：纯 CPU（默认，零依赖） */
    public static final String EP_CPU = "cpu";
    /** EP 取值：DirectML（Windows 通用 GPU，含 ARM64） */
    public static final String EP_DIRECTML = "directml";
    /** EP 取值：CUDA（仅 NVIDIA） */
    public static final String EP_CUDA = "cuda";
    /** EP 取值：按 directml → cuda → cpu 依次探测 */
    public static final String EP_AUTO = "auto";

    private final String requested;
    private final int intraOpThreads;
    private final int interOpThreads;

    /**
     * 进程级单例。由 {@code AiExecutionProviderConfig} 在容器启动时 {@link #install} 一次。
     *
     * <p>为什么用静态持有而不是构造注入：本项目的 AI 服务并非全部由 Spring 创建
     * （见 {@code AdversarialSchemeService.current()} 这个既有静态入口），
     * 静态单例能让四条推理链路（选择器 / 自对抗 / 道次 / 方案生成）共用同一份 EP 配置，
     * 不会出现「有的跑 GPU、有的跑 CPU」的口径分裂。未 install 时返回默认 CPU 工厂。</p>
     */
    private static volatile OnnxSessionFactory instance;

    /** 安装进程级工厂（容器启动时调用）。 */
    public static void install(OnnxSessionFactory f) {
        instance = f;
        log.info("AI 执行提供器工厂已安装: {}", f);
    }

    /** 取进程级工厂；未安装时给一个保守的 CPU 默认值。 */
    public static OnnxSessionFactory get() {
        OnnxSessionFactory f = instance;
        if (f == null) {
            synchronized (OnnxSessionFactory.class) {
                f = instance;
                if (f == null) {
                    f = new OnnxSessionFactory(EP_CPU, 0, 0);
                    instance = f;
                }
            }
        }
        return f;
    }

    /** 实际生效的 EP（构造后即固定；降级结果体现在此） */
    private volatile String effective = EP_CPU;
    /** 降级原因（null = 按请求成功） */
    private volatile String degradeReason;
    /** 探测时的详细过程，供 /api/ai/status 展示 */
    private final List<String> probeLog = new ArrayList<>();

    public OnnxSessionFactory(String requestedEp, int intraOpThreads, int interOpThreads) {
        String r = requestedEp == null || requestedEp.isBlank()
                ? EP_CPU : requestedEp.trim().toLowerCase(Locale.ROOT);
        if (!r.equals(EP_CPU) && !r.equals(EP_DIRECTML) && !r.equals(EP_CUDA) && !r.equals(EP_AUTO)) {
            log.warn("未知的 execution-provider: {}，回落 cpu", requestedEp);
            r = EP_CPU;
        }
        this.requested = r;
        this.intraOpThreads = intraOpThreads;
        this.interOpThreads = interOpThreads;
    }

    /**
     * 构建一组 SessionOptions：按请求注入 EP，失败则回落纯 CPU。
     *
     * <p><b>不抛异常</b>——GPU 不可用不是错误，只降级。</p>
     */
    public OrtSession.SessionOptions newSessionOptions() {
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        if (intraOpThreads > 0) {
            trySetIntraOp(options);
        }
        if (interOpThreads > 0) {
            trySetInterOp(options);
        }
        if (EP_CPU.equals(requested)) {
            effective = EP_CPU;
            return options;
        }
        List<String> chain = EP_AUTO.equals(requested)
                ? List.of(EP_DIRECTML, EP_CUDA)
                : List.of(requested);
        for (String ep : chain) {
            if (tryAppend(options, ep)) {
                effective = ep;
                degradeReason = null;
                log.info("AI 执行提供器已启用: {}（requested={}）", ep, requested);
                return options;
            }
        }
        // 全部失败 → 纯 CPU
        effective = EP_CPU;
        degradeReason = EP_AUTO.equals(requested)
                ? "directml / cuda 均不可用，已回落 CPU"
                : requested + " 不可用，已回落 CPU";
        log.warn("AI GPU 执行提供器不可用（{}），回落 CPU。探测过程: {}", degradeReason, probeLog);
        return options;
    }

    private boolean tryAppend(OrtSession.SessionOptions options, String ep) {
        try {
            if (EP_DIRECTML.equals(ep)) {
                // deviceId=0 = 默认适配器（枚举顺序即优先级）
                options.addDirectML(0);
                probeLog.add("directml: 已注册");
                return true;
            }
            if (EP_CUDA.equals(ep)) {
                options.addCUDA();
                probeLog.add("cuda: 已注册");
                return true;
            }
            return false;
        } catch (Throwable t) {
            // EP 类不存在（依赖没打进 jar）/ 驱动缺失 / 初始化失败 —— 都要能兜住。
            // 用 Throwable 而非 Exception：缺依赖时 JVM 抛的是 NoClassDefFoundError / UnsatisfiedLinkError。
            probeLog.add(ep + ": 不可用（" + t.getClass().getSimpleName() + ": " + t.getMessage() + "）");
            log.debug("EP {} 不可用: {}", ep, t.toString());
            return false;
        }
    }

    private void trySetIntraOp(OrtSession.SessionOptions options) {
        try {
            options.setIntraOpNumThreads(intraOpThreads);
        } catch (Throwable t) {
            log.debug("设置 intraOpNumThreads 失败（该版本可能不支持）: {}", t.toString());
        }
    }

    private void trySetInterOp(OrtSession.SessionOptions options) {
        try {
            options.setInterOpNumThreads(interOpThreads);
        } catch (Throwable t) {
            log.debug("设置 interOpNumThreads 失败（该版本可能不支持）: {}", t.toString());
        }
    }

    /** 从模型字节创建 session（沿用「传 byte[] 而非路径」的既有约定，避免 jar 内资源落临时文件）。 */
    public OrtSession createSession(OrtEnvironment env, byte[] model, OrtSession.SessionOptions options)
            throws OrtException {
        return env.createSession(model, options);
    }

    /** 实际生效的 EP。 */
    public String effective() {
        return effective;
    }

    /** 用户请求的 EP。 */
    public String requested() {
        return requested;
    }

    /** 是否真的跑在 GPU 上。 */
    public boolean isGpu() {
        return EP_DIRECTML.equals(effective) || EP_CUDA.equals(effective);
    }

    /** 降级说明（null = 未降级）。 */
    public String degradeReason() {
        return degradeReason;
    }

    /** 探测过程（供排障）。 */
    public List<String> probeLog() {
        synchronized (probeLog) {
            return List.copyOf(probeLog);
        }
    }

    /** {@code /api/ai/status} 用的一行摘要。 */
    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requested", requested);
        m.put("effective", effective);
        m.put("gpu", isGpu());
        m.put("degradeReason", degradeReason);
        m.put("probe", probeLog());
        m.put("platform", System.getProperty("os.arch") + "/" + System.getProperty("os.name"));
        m.put("jvmArch", System.getProperty("os.arch"));
        return m;
    }

    @Override
    public String toString() {
        return "OnnxSessionFactory{requested=" + requested + ", effective=" + effective
                + (degradeReason == null ? "" : ", degrade=" + degradeReason) + "}";
    }
}
