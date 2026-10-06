package com.sports.service.schedule;

import com.sports.collab.ScheduleCollaborationService;
import com.sports.repository.arrange.ArrangementRepository;
import com.sports.repository.arrange.ArrangementReservationRepository;
import com.sports.repository.event.EventRefereeRepository;
import com.sports.repository.event.EventRepository;
import com.sports.repository.event.EventScheduleRepository;
import com.sports.repository.registration.RegistrationRepository;
import com.sports.repository.venue.VenueRepository;
import com.sports.schedule.ai.AdversarialSchemeService;
import com.sports.schedule.analysis.LowerBoundEstimator;
import com.sports.schedule.opt.alns.AlnsImprover;
import com.sports.schedule.opt.fixopt.FixAndOptimizer;
import com.sports.schedule.opt.ga.GeneticAlgorithm;
import com.sports.schedule.opt.lns.LnsImprover;
import com.sports.schedule.opt.mnsa.MultiNeighborhoodAnnealer;
import com.sports.schedule.opt.solver.ScheduleOptimizer;
import com.sports.schedule.rule.RuleBasedScheduler;
import com.sports.schedule.verify.ScheduleVerifier;
import com.sports.service.arrange.ArrangementService;
import com.sports.service.arrange.ConflictService;
import com.sports.service.arrange.HeatStaggerService;
import com.sports.service.audit.AuditService;
import com.sports.service.protection.AdminTimeProtectionService;
import com.sports.service.system.SystemService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 装配自检测试：<b>精修链调参必须在「字段注入之后」被读到</b>。
 *
 * <p>背景（真实踩过的静默失效）：{@code ScheduleService} 的 {@code lnsRounds} / {@code gaPopulation}
 * / {@code mnsaIterations} / {@code alnsRounds} / {@code fixoptRounds} 等 10 个字段是
 * {@code @Value} <b>字段注入</b>——Spring 在<b>构造器执行完之后</b>才写值。旧实现在构造器里就把这些
 * 字段传给 {@code new ScheduleSolveComponent(...)}，那一刻全是 0；而组件对每个算法都以
 * 「&gt;0 才启用」为开关 ⇒ <b>生产环境整条精修链（GA/LNS/MNSA/ALNS/Fix-opt）从未运行过</b>，
 * 日志正常、接口正常、前端只是少了几个统计字段，没有任何报错。</p>
 *
 * <p>本测试用「构造之后才把值写进字段，然后断言组件看得见」来钉住这个语义——这正是 Spring 的真实时序。
 * 一旦有人把参数改回构造期快照（或把 supplier 换回直接传值），本测试立即失败。</p>
 */
@DisplayName("赛程服务装配：精修链调参必须在字段注入之后被读到")
class ScheduleServiceWiringTest {

    /**
     * 造一个「依赖全为 mock」的 ScheduleService，避免引入求解器与数据库。
     * 这里刻意手写而不走 {@code @InjectMocks}：本测试要验证的正是<b>构造之后</b>发生的字段注入，
     * 时序必须由测试自己精确控制。
     */
    private static ScheduleService newService() {
        return new ScheduleService(
                mock(EventScheduleRepository.class),
                mock(EventRepository.class),
                mock(RegistrationRepository.class),
                mock(ArrangementRepository.class),
                mock(ArrangementService.class),
                mock(SystemService.class),
                mock(ConflictService.class),
                mock(VenueRepository.class),
                mock(EventRefereeRepository.class),
                mock(ArrangementReservationRepository.class),
                mock(ScheduleOptimizer.class),
                mock(ScheduleVerifier.class),
                mock(LowerBoundEstimator.class),
                mock(LnsImprover.class),
                mock(GeneticAlgorithm.class),
                mock(MultiNeighborhoodAnnealer.class),
                mock(AlnsImprover.class),
                mock(FixAndOptimizer.class),
                mock(ScheduleCollaborationService.class),
                mock(RuleBasedScheduler.class),
                mock(AuditService.class),
                mock(AdminTimeProtectionService.class),
                mock(HeatStaggerService.class));
    }

    /** 模拟 Spring 的 @Value 字段注入（值取自 application.yml 的实际配置）。 */
    private static void injectValueFields(ScheduleService svc, int lnsRounds, int gaPopulation,
                                          int gaGenerations, int mnsaIterations, int alnsRounds,
                                          int fixoptRounds) throws Exception {
        set(svc, "lnsRounds", lnsRounds);
        set(svc, "lnsRoundMillis", 700L);
        set(svc, "gaPopulation", gaPopulation);
        set(svc, "gaGenerations", gaGenerations);
        set(svc, "gaMutationRate", 0.15);
        set(svc, "gaIndividualMillis", 300L);
        set(svc, "mnsaIterations", mnsaIterations);
        set(svc, "alnsRounds", alnsRounds);
        set(svc, "fixoptRounds", fixoptRounds);
        set(svc, "fixoptSliceMillis", 600L);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field f = ScheduleService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    @DisplayName("构造后才注入的 @Value 值，组件必须看得见（防构造期快照回归）")
    void tuningMustBeReadAfterFieldInjection() throws Exception {
        ScheduleService svc = newService();

        // 构造刚结束、还没「注入」——此刻组件不应认为精修链启用
        assertFalse(svc.solveComponent().refineChainEnabled(),
                "字段尚未注入时精修链应为关闭（构造期读到 0 是旧缺陷的成因，这里显式记录该事实）");

        // 模拟 Spring 完成 @Value 字段注入（application.yml 的实际取值）
        injectValueFields(svc, 2, 6, 2, 120, 8, 2);

        ScheduleTuning t = svc.solveComponent().tuning();
        assertTrue(t.gaEnabled(), "GA 应在注入后启用（种群 6 ≥2 且 2 代 ≥1）");
        assertTrue(t.lnsEnabled(), "LNS 应在注入后启用（2 轮 > 0）");
        assertTrue(t.mnsaEnabled(), "MNSA 应在注入后启用（120 步 > 0）");
        assertTrue(t.alnsEnabled(), "ALNS 应在注入后启用（8 轮 > 0）");
        assertTrue(t.fixoptEnabled(), "Fix-and-Optimize 应在注入后启用（2 轮 > 0）");
        assertTrue(svc.solveComponent().refineChainEnabled(), "整条精修链应处于启用态");

        // describe() 同时给出「开关」与「实际取值」——「没跑」与「跑了没改进」才分得清
        String desc = t.describe();
        assertTrue(desc.contains("GA=开(6种群×2代"), "摘要应含 GA 实参: " + desc);
        assertTrue(desc.contains("ALNS=开(8轮)"), "摘要应含 ALNS 实参: " + desc);
    }

    @Test
    @DisplayName("配置全 0 时如实报告未启用，不得伪报为已启用")
    void allDisabledIsReportedHonestly() throws Exception {
        ScheduleService svc = newService();
        injectValueFields(svc, 0, 0, 0, 0, 0, 0);

        ScheduleTuning t = svc.solveComponent().tuning();
        assertFalse(t.refineChainEnabled(), "全 0 配置下精修链应为关闭");
        assertTrue(t.describe().contains("GA=关"), "关闭态也要如实打印: " + t.describe());
    }

    @Test
    @DisplayName("自对抗服务用时取值：构造期为空时不缓存，后续绑定必须可见")
    void adversarialServiceIsReadAtUseTime() {
        AdversarialSchemeService[] holder = new AdversarialSchemeService[1];
        ScheduleSolveComponent component = new ScheduleSolveComponent(
                mock(ScheduleOptimizer.class), mock(RuleBasedScheduler.class), mock(GeneticAlgorithm.class),
                mock(LnsImprover.class), mock(MultiNeighborhoodAnnealer.class), mock(AlnsImprover.class),
                mock(FixAndOptimizer.class), new ScheduleBuildComponent(null, null, null),
                () -> new ScheduleTuning(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                () -> holder[0]);

        // 构造期静态入口还没绑定（AdversarialSchemeService 的创建顺序由 Spring 决定）
        assertNull(component.adversarial(), "未绑定时应返回 null，由调用方判空降级");

        // 之后容器才创建出自对抗服务
        AdversarialSchemeService later = mock(AdversarialSchemeService.class);
        holder[0] = later;
        assertSame(later, component.adversarial(),
                "用时取值 ⇒ 后续绑定必须可见；若在此处缓存构造期的 null，AI 自对抗会永久静默降级");
    }
}
