package com.sports.service.schedule;

import com.sports.entity.event.Event;
import com.sports.repository.event.EventScheduleRepository;
import com.sports.schedule.core.primitive.Pool;
import com.sports.schedule.core.primitive.Unit;
import com.sports.schedule.core.primitive.Window;
import com.sports.service.arrange.ArrangementService;
import com.sports.service.arrange.ConflictService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 落库路径（MultiStartPlacementRunner）的径赛串行回归测试。
 *
 * <p>为什么需要它：{@code AutoSchedulePreparer} 把 trackSlots 修正为「径赛场地数」之后，
 * 落库路径会**按趟重建**径赛池。旧实现只把 {@code mainVenue} 一条场地塞进池里，
 * 于是双跑道（trackSlots=2）时两个并发槽位都绑到同一条跑道 —— 准备阶段修好了，
 * 落库时又被破坏，等于「一条跑道同一时刻跑两个径赛项目」。</p>
 *
 * <p>契约：并发槽位数 = 径赛场地数，且每个槽位绑定**不同**场地。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MultiStartPlacementRunnerTrackSerialTest {

    @Mock private EventScheduleRepository scheduleRepository;
    @Mock private ArrangementService arrangementService;
    @Mock private ConflictService conflictService;
    @Mock private ScheduleBuildComponent buildComponent;
    @Mock private SchedulePlacementComponent placementComponent;

    @InjectMocks private MultiStartPlacementRunner runner;

    // ---------- 纯函数层：trackPoolForPass ----------

    @Test
    void singleTrackVenueStillSerialWithOneSlot() {
        Pool p = MultiStartPlacementRunner.trackPoolForPass(1, "田径场A", List.of("田径场A"));
        assertEquals(1, p.slots, "单跑道只能 1 个并发槽位（严格串行）");
        assertEquals(List.of("田径场A"), p.venueOf);
        assertFalse(p.venueShortage);
    }

    @Test
    void multiTrackVenueBindsDistinctVenuesPerSlot() {
        // 双跑道 parallelMax 误配成 8 也不影响：trackSlots 由场地数决定 = 2
        Pool p = MultiStartPlacementRunner.trackPoolForPass(
                2, "田径场A", List.of("田径场A", "田径场B"));
        assertEquals(2, p.slots);
        assertEquals(List.of("田径场A", "田径场B"), p.venueOf,
                "两个并发槽位必须各绑一条不同跑道，不得复用同一条");
        assertFalse(p.venueShortage, "场地数=槽位数，不应报场地短缺");
    }

    @Test
    void parallelMaxNeverLeaksIntoTrackSlotCount() {
        // 旧缺陷的触发条件：单跑道 parallelMax=8 被当成 8 路并行。
        // 准备阶段已把 trackSlots 收敛到 1；这里确认落库池也不会凭空扩成 8。
        Pool p = MultiStartPlacementRunner.trackPoolForPass(1, "田径场A", List.of("田径场A"));
        assertEquals(1, p.slots);
        assertEquals(1, p.venueOf.size());
        assertEquals("田径场A", p.venueOf.get(0));
    }

    @Test
    void emptyVenueListFallsBackToMainVenue() {
        Pool p = MultiStartPlacementRunner.trackPoolForPass(1, "田径场A", List.of());
        assertEquals(List.of("田径场A"), p.venueOf);
        Pool q = MultiStartPlacementRunner.trackPoolForPass(1, "田径场A", null);
        assertEquals(List.of("田径场A"), q.venueOf);
    }

    // ---------- 集成层：runPass 确实使用了全量场地清单 ----------

    private Unit trackUnit(long id, String name) {
        Event e = Event.builder().id(id).name(name).code("T" + id).track(true).build();
        Unit u = new Unit(e, "高一");
        u.track = true;
        u.participants = 8;   // >0，才会走到 resolvePool/placeOne
        u.duration = 6;
        u.rawDuration = 6;
        u.rounds = 1;
        return u;
    }

    private MultiStartPlacementRunner.PassEnv env(int trackSlots, List<String> trackVenues, List<Unit> units) {
        Set<Long> withRegs = new java.util.HashSet<>();
        for (Unit u : units) withRegs.add(u.event.getId());
        Map<String, String> codeToName = new LinkedHashMap<>();
        Map<String, Integer> codeToParallelMax = new LinkedHashMap<>();
        Map<Long, String> event2Group = new LinkedHashMap<>();   // 无分组 → 走 placeOne 普通分支
        return new MultiStartPlacementRunner.PassEnv(
                units,
                List.of(new Window(1, "2026-09-20", "AM", 480, 600)),
                trackSlots,
                1,
                "田径场A",
                trackVenues,
                List.of("田赛区"),
                "TRACK_A",
                Set.of("FIELD_A"),
                codeToName,
                codeToParallelMax,
                event2Group,
                5,
                5,
                1.5,
                Map.of(),
                withRegs,
                Map.of(),
                null);
    }

    @Test
    void runPassUsesAllTrackVenuesForMultiTrack() {
        List<Unit> units = List.of(trackUnit(9101L, "100米"), trackUnit(9102L, "200米"));
        when(buildComponent.resolvePool(any(), any(), any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt()))
                .thenAnswer(inv -> inv.getArgument(1));   // 返回传入的 trackPool

        MultiStartPlacementRunner.PassEnv e = env(2, List.of("田径场A", "田径场B"), units);
        runner.runPass(e, List.of(0, 1));

        ArgumentCaptor<Pool> captor = ArgumentCaptor.forClass(Pool.class);
        verify(buildComponent, atLeastOnce()).resolvePool(any(), captor.capture(), any(), any(),
                any(), any(), any(), any(), anyInt(), anyInt());
        Pool used = captor.getValue();
        assertEquals(2, used.slots, "双跑道 → 2 个并发槽位");
        assertEquals(List.of("田径场A", "田径场B"), used.venueOf,
                "落库路径的径赛池必须绑定全部径赛场地（每槽一条跑道）");
    }

    @Test
    void runPassSingleTrackKeepsSerial() {
        List<Unit> units = List.of(trackUnit(9101L, "100米"));
        when(buildComponent.resolvePool(any(), any(), any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt()))
                .thenAnswer(inv -> inv.getArgument(1));

        MultiStartPlacementRunner.PassEnv e = env(1, List.of("田径场A"), units);
        runner.runPass(e, List.of(0));

        ArgumentCaptor<Pool> captor = ArgumentCaptor.forClass(Pool.class);
        verify(buildComponent, atLeastOnce()).resolvePool(any(), captor.capture(), any(), any(),
                any(), any(), any(), any(), anyInt(), anyInt());
        Pool used = captor.getValue();
        assertEquals(1, used.slots, "单跑道只能 1 个槽位 → 径赛严格串行");
        assertEquals(List.of("田径场A"), used.venueOf);
    }

    @Test
    void runPassWithoutVenueListFallsBackSafely() {
        // 旧配置（无类型信息）不会因新字段为空而炸：回退主场地
        List<Unit> units = List.of(trackUnit(9101L, "100米"));
        when(buildComponent.resolvePool(any(), any(), any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt()))
                .thenAnswer(inv -> inv.getArgument(1));

        MultiStartPlacementRunner.PassEnv e = env(1, new ArrayList<>(), units);
        assertDoesNotThrow(() -> runner.runPass(e, List.of(0)));
        ArgumentCaptor<Pool> captor = ArgumentCaptor.forClass(Pool.class);
        verify(buildComponent, atLeastOnce()).resolvePool(any(), captor.capture(), any(), any(),
                any(), any(), any(), any(), anyInt(), anyInt());
        assertEquals(List.of("田径场A"), captor.getValue().venueOf);
    }
}
