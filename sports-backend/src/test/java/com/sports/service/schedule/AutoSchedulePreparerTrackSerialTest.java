package com.sports.service.schedule;

import com.sports.entity.venue.Venue;
import com.sports.repository.venue.VenueRepository;
import com.sports.schedule.core.primitive.Window;
import com.sports.service.protection.AdminTimeProtectionService;
import com.sports.service.system.SystemService;
import com.sports.schedule.core.primitive.Pool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 径赛严格串行（无并行，除非多场地）的回归测试。
 *
 * <p>核心契约：径赛并发位 = 径赛场地数量，与单场地的 parallelMax 无关——
 * 即便把某条跑道的 parallelMax 误配成 8（以为 8 条道 = 8 个项目并行），
 * 径赛仍只能串行，trackSlots 不得超过径赛场地数。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AutoSchedulePreparerTrackSerialTest {

    @Mock private ScheduleBuildComponent buildComponent;
    @Mock private SystemService systemService;
    @Mock private VenueRepository venueRepository;
    @Mock private AdminTimeProtectionService protectionService;

    @InjectMocks private AutoSchedulePreparer preparer;

    // ---------- 纯函数层：deriveTrackSlots ----------

    private Map<String, Object> trackVenue(String code, int parallelMax) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("name", code);
        m.put("type", "track");
        m.put("parallelMax", parallelMax);
        return m;
    }

    @Test
    void trackSlotsIgnoresParallelMaxAndEqualsVenueCount() {
        // 单跑道，parallelMax 故意错配成 8 → 仍应为 1（严格串行）
        List<Map<String, Object>> one = List.of(trackVenue("T1", 8));
        assertEquals(1, AutoSchedulePreparer.deriveTrackSlots(one),
                "单条跑道即便 parallelMax=8 也只能是 1 个串行槽位");

        // 双跑道 → 2（仅多场地才并行，且每条场地 1 个独立串行槽位）
        List<Map<String, Object>> two = List.of(trackVenue("T1", 8), trackVenue("T2", 8));
        assertEquals(2, AutoSchedulePreparer.deriveTrackSlots(two));

        // 三跑道 → 3
        List<Map<String, Object>> three = List.of(
                trackVenue("T1", 8), trackVenue("T2", 8), trackVenue("T3", 8));
        assertEquals(3, AutoSchedulePreparer.deriveTrackSlots(three));

        // 空 → 保底 1
        assertEquals(1, AutoSchedulePreparer.deriveTrackSlots(List.of()));
        assertEquals(1, AutoSchedulePreparer.deriveTrackSlots(null));
    }

    // ---------- 集成层：prepare() 经 venueRepository 取径赛场地 ----------

    private Venue track(String code, int parallelMax, int sort) {
        return Venue.builder().id((long) sort).code(code).name(code)
                .type("track").parallelMax(parallelMax).sortOrder(sort).enabled(true).build();
    }

    private AutoScheduleContext prepareWithTrackVenues(Venue... venues) {
        when(venueRepository.findByEnabledTrueOrderBySortOrderAsc()).thenReturn(List.of(venues));
        when(systemService.getArrangeRule()).thenReturn(null);
        when(buildComponent.mergeConfig(any())).thenReturn(new LinkedHashMap<>());
        when(buildComponent.buildUnits(any(), any())).thenReturn(new ArrayList<>());
        when(buildComponent.buildWindows(any())).thenReturn(
                List.of(new Window(1, "2026-09-20", "AM", 480, 600)));
        return preparer.prepare(new LinkedHashMap<>());
    }

    @Test
    void prepare_singleTrackVenueParallelMax8StillSerial() {
        AutoScheduleContext c = prepareWithTrackVenues(track("田径场A", 8, 1));
        assertEquals(1, c.trackSlots,
                "单条跑道 parallelMax=8 绝不能被当成 8 路并行，径赛必须串行（trackSlots=1）");
        // 径赛池的场地清单应只有这 1 条，且其容量=1（串行）
        assertNotNull(c.trackPool);
        assertEquals(1, c.trackPool.slots);
        assertFalse(c.trackPool.venueShortage, "单跑道=单槽位，不应报场地短缺");
        // 落库路径（MultiStartPlacementRunner）按趟重建池，靠这个清单绑定场地——必须也只含 1 条
        assertEquals(List.of("田径场A"), c.trackVenues,
                "传给落库路径的径赛场地清单必须是全量（单跑道=1 条）");
        assertEquals(List.of("田径场A"), c.trackPool.venueOf);
    }

    @Test
    void prepare_multiTrackVenueGivesParallelOnlyViaVenueCount() {
        // 双跑道，parallelMax 仍故意错配成 8 → 应为 2（=场地数），绝不是 8（parallelMax）
        AutoScheduleContext c = prepareWithTrackVenues(track("田径场A", 8, 1), track("田径场B", 8, 2));
        assertEquals(2, c.trackSlots,
                "双跑道应得 2 个串行槽位（多场地才并行），不得取 parallelMax=8");
        assertNotNull(c.trackPool);
        assertEquals(2, c.trackPool.slots);
        // 两个槽位应各自绑定不同跑道，而非复用同一条 → 无短缺
        assertFalse(c.trackPool.venueShortage, "双跑道=双槽位，不应报场地短缺");
        assertEquals(2, c.trackPool.venueOf.size());
        // 两个槽位各绑一条不同跑道；且全量清单要能一路传到落库路径
        assertEquals(List.of("田径场A", "田径场B"), c.trackVenues,
                "传给落库路径的径赛场地清单必须含全部 2 条跑道");
        assertEquals(List.of("田径场A", "田径场B"), c.trackPool.venueOf,
                "两个并发槽位必须绑不同跑道，否则等于一条跑道同时跑两个径赛项目");
    }
}
