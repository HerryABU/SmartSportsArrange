package com.sports.service;

import com.sports.entity.Event;
import com.sports.repository.EventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 项目服务测试。
 *
 * <p>重点覆盖「部分更新（PATCH）语义」：Event 多数字段带 Java 初始化默认值
 * （track=true、laneCount=8 …），若按“非空即覆盖”实现，只改一个字段的批量修改
 * 会把其它字段改回默认值（典型：批量改并发把田赛改成径赛）。</p>
 */
@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    @Mock private EventRepository eventRepository;
    @Mock private ExcelService excelService;

    @InjectMocks private EventService eventService;

    private Event fieldEvent() {
        return Event.builder().id(96L).code("SWIM_M").name("50米蛙泳(男子)")
                .track(false).laneCount(0).defaultLanes(1).category("田赛")
                .gradeGroup("高一年级").genderLimit("男子组").isEnabled(true).build();
    }

    private void stubSave(Long id, Event existing) {
        when(eventRepository.findById(id)).thenReturn(Optional.of(existing));
        when(eventRepository.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /** 只提交 concurrency 时，田赛类型/道次/分类必须保持不变 */
    @Test
    void partialUpdateKeepsUntouchedFields() {
        Event existing = fieldEvent();
        stubSave(96L, existing);

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("concurrency", 4);

        Event saved = eventService.update(96L, patch);

        assertEquals(4, saved.getConcurrency());
        assertEquals(Boolean.FALSE, saved.getTrack(), "只改并发不应把田赛改成径赛");
        assertEquals(0, saved.getLaneCount(), "田赛道次应保持 0");
        assertEquals("田赛", saved.getCategory());
    }

    /** 显式提交 isTrack=true 时仍应正常生效（不是“什么都不改”） */
    @Test
    void explicitTrackChangeStillApplies() {
        Event existing = fieldEvent();
        stubSave(96L, existing);

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("isTrack", true);
        patch.put("laneCount", 6);

        Event saved = eventService.update(96L, patch);

        assertEquals(Boolean.TRUE, saved.getTrack());
        assertEquals(6, saved.getLaneCount());
    }
}
