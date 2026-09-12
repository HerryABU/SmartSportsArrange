package com.sports.service;

import com.alibaba.excel.EasyExcel;
import com.sports.entity.Event;
import com.sports.repository.EventRepository;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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

    /**
     * Excel 表格2 导入：O 列（场地编码）应落入 defaultVenueCode。
     * 直接覆盖「Excel 案例也要有」的核对项——游泳指定独立场馆编码 SWIM。
     */
    @Test
    void importTable2RowPopulatesDefaultVenueCode() {
        List<String> header = List.of("代码", "项目", "是否田径", "道次", "性别", "年级组",
                "是否团体", "团体人数", "并数/项目内并发", "场地", "最大用时(分)", "间隔(分)",
                "顺序号", "并行捆绑组", "场地编码");
        List<String> data = List.of("SWIM_M", "50米蛙泳(男子)", "否", "0",
                "", "", "", "", "", "", "", "", "", "", "SWIM");
        String csv = String.join(",", header) + "\n" + String.join(",", data);

        MultipartFile file = new CsvMultipartFile("events.csv", csv);
        when(eventRepository.existsByCode("SWIM_M")).thenReturn(false);
        List<Event> captured = new ArrayList<>();
        when(eventRepository.save(any(Event.class))).thenAnswer(inv -> {
            Event e = inv.getArgument(0);
            captured.add(e);
            return e;
        });

        Map<String, Object> result = eventService.importEvents(file);

        assertEquals(1, result.get("success"), "应成功导入 1 行");
        assertEquals("table2", result.get("layout"));
        assertEquals(1, captured.size());
        assertEquals("SWIM", captured.get(0).getDefaultVenueCode(), "O 列场地编码应写入 defaultVenueCode");
    }

    /**
     * Excel 导出：项目的 defaultVenueCode 应出现在第 O 列（索引 14）。
     * 覆盖「Excel 案例也要有」的反向核对——导出文件含场地编码。
     */
    @Test
    void exportEventsWritesVenueCodeInColumnO() throws IOException {
        Event swim = Event.builder().id(97L).code("SWIM_M").name("50米蛙泳(男子)")
                .track(false).laneCount(0).defaultLanes(1).category("田赛")
                .gradeGroup("高一年级").genderLimit("男子组").isEnabled(true)
                .defaultVenueCode("SWIM").sortOrder(0).build();
        when(eventRepository.findByIsEnabledTrueOrderBySortOrderAsc()).thenReturn(List.of(swim));

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        HttpServletResponse resp = mock(HttpServletResponse.class);
        ServletOutputStream sos = new ServletOutputStream() {
            @Override public void write(int b) { bos.write(b); }
            @Override public boolean isReady() { return true; }
            @Override public void setWriteListener(jakarta.servlet.WriteListener wl) { }
        };
        when(resp.getOutputStream()).thenReturn(sos);

        eventService.exportEvents(resp);

        List<Map<Integer, String>> rows = EasyExcel.read(new java.io.ByteArrayInputStream(bos.toByteArray()))
                .sheet().headRowNumber(0).doReadSync();
        Map<Integer, String> dataRow = rows.stream()
                .filter(r -> "SWIM_M".equals(r.get(0))).findFirst().orElseThrow();
        assertEquals("SWIM", dataRow.get(14), "导出的第 O 列应为场地编码 SWIM");
    }

    /** 最小 MultipartFile 桩，仅支撑 CSV 导入路径（getBytes / getOriginalFilename） */
    static class CsvMultipartFile implements MultipartFile {
        private final String name;
        private final byte[] bytes;
        CsvMultipartFile(String name, String content) {
            this.name = name;
            this.bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        public String getName() { return name; }
        public String getOriginalFilename() { return name; }
        public String getContentType() { return "text/csv"; }
        public boolean isEmpty() { return bytes.length == 0; }
        public long getSize() { return bytes.length; }
        public byte[] getBytes() { return bytes; }
        public InputStream getInputStream() { return new java.io.ByteArrayInputStream(bytes); }
        public void transferTo(java.io.File dest) throws IOException { java.nio.file.Files.write(dest.toPath(), bytes); }
    }
}
