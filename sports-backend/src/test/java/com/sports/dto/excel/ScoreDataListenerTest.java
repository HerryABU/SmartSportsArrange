package com.sports.dto.excel;

import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.metadata.holder.ReadRowHolder;
import com.alibaba.excel.read.metadata.holder.ReadSheetHolder;
import com.sports.entity.*;
import com.sports.repository.ArrangementRepository;
import com.sports.repository.AthleteRepository;
import com.sports.repository.EventRepository;
import com.sports.repository.ResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 成绩导入监听器测试。
 * 覆盖：R-2 非完赛标记（DNF/DNS/DSQ）识别为独立状态、正常数值成绩不受影响。
 */
@ExtendWith(MockitoExtension.class)
class ScoreDataListenerTest {

    @Mock private ResultRepository resultRepository;
    @Mock private EventRepository eventRepository;
    @Mock private AthleteRepository athleteRepository;
    @Mock private ArrangementRepository arrangementRepository;

    private ScoreDataListener listener;

    @BeforeEach
    void setup() {
        listener = new ScoreDataListener(resultRepository, eventRepository, athleteRepository, arrangementRepository);
    }

    private AnalysisContext ctx() {
        AnalysisContext ctx = mock(AnalysisContext.class);
        ReadRowHolder row = mock(ReadRowHolder.class);
        when(ctx.readRowHolder()).thenReturn(row);
        when(row.getRowIndex()).thenReturn(0);
        when(ctx.readSheetHolder()).thenReturn(null);
        return ctx;
    }

    private ScoreExcelModel model(String rawTime) {
        ScoreExcelModel m = new ScoreExcelModel();
        m.setEventCode("M100");
        m.setAthleteNumber("1001");
        m.setRawTime(rawTime);
        return m;
    }

    private void stubBasics() {
        Event e = Event.builder().id(1L).code("M100").name("100米").build();
        when(eventRepository.findByCode("M100")).thenReturn(Optional.of(e));
        Athlete a = Athlete.builder().id(1L).name("张三").number("1001").grade("高一").gender("男")
                .classInfo(ClassInfo.builder().id(1L).name("高一1班").grade("高一").build()).build();
        when(athleteRepository.findByNumber("1001")).thenReturn(Optional.of(a));
        when(resultRepository.findByEventIdAndAthleteId(1L, 1L)).thenReturn(Optional.empty());
        when(arrangementRepository.findByEventIdAndAthleteId(1L, 1L)).thenReturn(Optional.empty());
    }

    /** R-2：DNF 标记应被识别为独立状态 dnf，而非静默当有效成绩（避免被算入计分/排在冠军前） */
    @Test
    void import_dnfTokenSetsNonFinishStatus() {
        stubBasics();
        ArgumentCaptor<Result> cap = ArgumentCaptor.forClass(Result.class);
        when(resultRepository.save(cap.capture())).thenAnswer(inv -> inv.getArgument(0));

        listener.invoke(model("DNF"), ctx());

        assertEquals("dnf", cap.getValue().getStatus(), "DNF 应标记为非完赛状态");
        assertNull(cap.getValue().getTimeSeconds(), "非完赛不应有成绩秒数");
        assertEquals(0, listener.getErrorCount(), "合法非完赛标记不应计入错误");
    }

    /** R-2：DNS / DSQ 同样应被识别（大小写不敏感） */
    @Test
    void import_dnsAndDsqTokensRecognized() {
        stubBasics();
        ArgumentCaptor<Result> cap = ArgumentCaptor.forClass(Result.class);
        when(resultRepository.save(cap.capture())).thenAnswer(inv -> inv.getArgument(0));

        listener.invoke(model("dns"), ctx());
        listener.invoke(model("DSQ"), ctx());

        List<Result> saved = cap.getAllValues();
        assertEquals("dns", saved.get(0).getStatus());
        assertEquals("dsq", saved.get(1).getStatus());
    }

    /** 回归：正常数值成绩仍按 valid 落库且 timeSeconds 正确解析 */
    @Test
    void import_numericTimeStaysValid() {
        stubBasics();
        ArgumentCaptor<Result> cap = ArgumentCaptor.forClass(Result.class);
        when(resultRepository.save(cap.capture())).thenAnswer(inv -> inv.getArgument(0));

        listener.invoke(model("12.34"), ctx());

        assertEquals("valid", cap.getValue().getStatus());
        assertEquals(12.34, cap.getValue().getTimeSeconds(), 0.001);
        assertEquals(0, listener.getErrorCount());
    }
}
