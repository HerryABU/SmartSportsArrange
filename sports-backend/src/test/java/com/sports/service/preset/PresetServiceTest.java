package com.sports.service.preset;

import com.sports.entity.preset.EventTemplatePreset;
import com.sports.entity.preset.ScheduleOrderPreset;
import com.sports.repository.preset.EventTemplatePresetRepository;
import com.sports.repository.preset.ScheduleOrderPresetRepository;
import com.sports.service.system.SystemService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 命名预设方案单测：关注 JSON 快照往返、软删/恢复、重名校验、内置不可删、应用灌回。 */
@ExtendWith(MockitoExtension.class)
class PresetServiceTest {

    @Mock private ScheduleOrderPresetRepository orderRepo;
    @Mock private SystemService systemService;
    @InjectMocks private ScheduleOrderPresetService orderService;

    @Mock private EventTemplatePresetRepository templateRepo;
    // 两个 @InjectMocks 服务能力不同，手动构造第二个
    private EventTemplatePresetService templateService;

    private EventTemplatePresetService templateSvc() {
        if (templateService == null) templateService = new EventTemplatePresetService(templateRepo);
        return templateService;
    }

    // ==================== 编排顺序预设 ====================

    @Test
    @DisplayName("另存为：快照写进 JSON，重名被拒")
    void orderSaveSnapshotAndDuplicateName() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("meetName", "校运会");
        snapshot.put("eventOrder", List.of(1, 2, 3));
        when(orderRepo.findByName("短跑优先")).thenReturn(Optional.empty());
        when(orderRepo.count()).thenReturn(0L);
        when(orderRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> saved = orderService.save("短跑优先", null, snapshot);

        assertEquals("短跑优先", saved.get("name"));
        assertEquals("校运会", ((Map<?, ?>) saved.get("snapshot")).get("meetName"));

        when(orderRepo.findByName("短跑优先")).thenReturn(Optional.of(order(1L, "短跑优先")));
        assertThrows(IllegalArgumentException.class, () -> orderService.save("短跑优先", null, snapshot));
    }

    @Test
    @DisplayName("应用：快照灌回 SystemService.saveMeetSchedule，而非另起一套口径")
    void orderApplyFeedsSystemService() {
        ScheduleOrderPreset p = order(1L, "田赛优先");
        p.setSnapshotJson("{\"meetName\":\"春季运动会\",\"eventOrder\":[9,8,7]}");
        when(orderRepo.findById(1L)).thenReturn(Optional.of(p));
        when(systemService.saveMeetSchedule(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> out = orderService.apply(1L);

        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(systemService).saveMeetSchedule(cap.capture());
        assertEquals("春季运动会", cap.getValue().get("meetName"));
        assertEquals(List.of(9, 8, 7), cap.getValue().get("eventOrder"));
        assertEquals("田赛优先", out.get("name"));
    }

    @Test
    @DisplayName("删除是软删（打时间戳走 save），不用批量 UPDATE")
    void orderDeleteIsSoft() {
        ScheduleOrderPreset p = order(1L, "方案A");
        when(orderRepo.findById(1L)).thenReturn(Optional.of(p));

        orderService.delete(1L);

        verify(orderRepo).save(p);
        assertNotNull(p.getDeletedAt());
    }

    @Test
    @DisplayName("恢复走原生 SQL")
    void orderRestore() {
        when(orderRepo.restore(1L)).thenReturn(1);
        assertEquals(1, orderService.restore(1L));
        verify(orderRepo).restore(1L);
    }

    // ==================== 项目模板预设 ====================

    @Test
    @DisplayName("首次为空时幂等铺内置方案（21 条模板，含混合组接力）")
    void templateSeedsBuiltin() throws Exception {
        when(templateRepo.count()).thenReturn(0L);
        when(templateRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        templateSvc().listWithDefaults();

        ArgumentCaptor<EventTemplatePreset> cap = ArgumentCaptor.forClass(EventTemplatePreset.class);
        verify(templateRepo).save(cap.capture());
        EventTemplatePreset builtin = cap.getValue();
        assertEquals("系统默认", builtin.getName());
        assertTrue(Boolean.TRUE.equals(builtin.getBuiltin()));
        assertTrue(builtin.getTemplatesJson().contains("混合组"));
        assertTrue(builtin.getTemplatesJson().contains("4×100米接力"));
        // 内置模板数量锁定 21 条：防止以后增删条目时注释/文案与实际数据再次漂移
        assertEquals(21, new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(builtin.getTemplatesJson()).size());
    }

    @Test
    @DisplayName("已有方案时不再铺内置")
    void templateDoesNotReseed() {
        when(templateRepo.count()).thenReturn(1L);
        templateSvc().listWithDefaults();
        verify(templateRepo, never()).save(any());
    }

    @Test
    @DisplayName("内置方案不可改名/删除，重名被拒")
    void templateBuiltinProtectedAndDuplicateName() {
        EventTemplatePreset builtin = EventTemplatePreset.builder()
                .id(1L).name("系统默认").builtin(true).templatesJson("[]").build();
        when(templateRepo.findById(1L)).thenReturn(Optional.of(builtin));
        assertThrows(IllegalArgumentException.class, () -> templateSvc().delete(1L));
        assertThrows(IllegalArgumentException.class,
                () -> templateSvc().update(1L, "改名", null, List.of()));

        EventTemplatePreset custom = EventTemplatePreset.builder()
                .id(2L).name("我的模板").builtin(false).templatesJson("[]").build();
        EventTemplatePreset otherSameName = EventTemplatePreset.builder()
                .id(99L).name("我的模板").builtin(false).templatesJson("[]").build();
        when(templateRepo.findById(2L)).thenReturn(Optional.of(custom));
        when(templateRepo.findByName("我的模板")).thenReturn(Optional.of(otherSameName));
        assertThrows(IllegalArgumentException.class,
                () -> templateSvc().update(2L, "我的模板", null, null));
    }

    @Test
    @DisplayName("自定义方案删除是软删，恢复走原生 SQL")
    void templateSoftDeleteAndRestore() {
        EventTemplatePreset custom = EventTemplatePreset.builder()
                .id(2L).name("我的模板").builtin(false).templatesJson("[]").build();
        when(templateRepo.findById(2L)).thenReturn(Optional.of(custom));
        templateSvc().delete(2L);
        verify(templateRepo).save(custom);
        assertNotNull(custom.getDeletedAt());

        when(templateRepo.restore(2L)).thenReturn(1);
        assertEquals(1, templateSvc().restore(2L));
        verify(templateRepo).restore(2L);
    }

    @Test
    @DisplayName("模板 JSON 往返：保存后再读出结构不丢")
    void templateJsonRoundTrip() {
        Map<String, Object> tpl = new LinkedHashMap<>();
        tpl.put("name", "100米");
        tpl.put("eventType", "径赛");
        tpl.put("gender", "男子组");
        tpl.put("category", "跑步类");
        when(templateRepo.findByName("自定义")).thenReturn(Optional.empty());
        when(templateRepo.count()).thenReturn(1L);
        when(templateRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> saved = templateSvc().save("自定义", null, List.of(tpl));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> out = (List<Map<String, Object>>) saved.get("templates");
        assertEquals(1, out.size());
        assertEquals("100米", out.get(0).get("name"));
        assertEquals("跑步类", out.get(0).get("category"));
    }

    private static ScheduleOrderPreset order(Long id, String name) {
        return ScheduleOrderPreset.builder().id(id).name(name).snapshotJson("{}").sortOrder(0).build();
    }
}
