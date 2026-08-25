package com.sports.service;

import com.sports.entity.Athlete;
import com.sports.entity.ClassInfo;
import com.sports.repository.SystemConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 号码簿规则服务测试。
 * 重点验证模板变量渲染、年级映射、班级号提取、补零、性别代码。
 */
@ExtendWith(MockitoExtension.class)
class NumberRuleServiceTest {

    @Mock
    private SystemConfigRepository systemConfigRepository;

    @InjectMocks
    private NumberRuleService numberRuleService;

    private Athlete athlete(String grade, String gender) {
        return Athlete.builder().id(1L).name("张三").grade(grade).gender(gender).build();
    }

    private ClassInfo classInfoByName(String name) {
        return ClassInfo.builder().id(1L).name(name).build();
    }

    @Test
    void generateNumber_defaultTemplate_gradeClassSeq() {
        // 高一年级 -> 10, 班级名"高一3班"提取尾号3 -> 03, seq=1 -> 01
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("template", "{grade}{class}{seq:02d}");
        rule.put("auto_pad_zero", true);

        String num = numberRuleService.generateNumber(rule, athlete("高一年级", "M"), classInfoByName("高一3班"), 1);
        assertEquals("100301", num);
    }

    @Test
    void generateNumber_schoolCodeAndYearAndWidth() {
        int year = LocalDate.now().getYear() % 100;
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("template", "{school_code}{year}{grade}{seq:03d}");
        rule.put("school_code", "01");
        rule.put("auto_pad_zero", true);

        String num = numberRuleService.generateNumber(rule, athlete("高一年级", "M"), classInfoByName("高一1班"), 5);
        assertEquals("01" + year + "10" + "005", num);
    }

    @Test
    void generateNumber_genderCode() {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("template", "{gender}");

        assertEquals("M", numberRuleService.generateNumber(rule, athlete("高一", "M"), classInfoByName("x"), 1));
        assertEquals("F", numberRuleService.generateNumber(rule, athlete("高一", "F"), classInfoByName("x"), 1));
    }

    @Test
    void generateNumber_gradeNameAndClassName() {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("template", "{grade_name}-{class_name}");

        String num = numberRuleService.generateNumber(rule, athlete("初一年级", "M"), classInfoByName("初一2班"), 1);
        assertEquals("初一年级-初一2班", num);
    }

    @Test
    void generateNumber_customGradeMappingOverride() {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("template", "{grade}{class}{seq:02d}");
        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("初一年级", "A");
        rule.put("grade_mapping", mapping);

        // 自定义映射：初一年级 -> A；班级用 classOrder=5 -> 05
        ClassInfo ci = ClassInfo.builder().id(1L).name("初一X班").classOrder(5).build();
        String num = numberRuleService.generateNumber(rule, athlete("初一年级", "M"), ci, 2);
        assertEquals("A0502", num);
    }

    @Test
    void generateNumber_usesClassOrderBeforeName() {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("template", "{class}");
        rule.put("auto_pad_zero", false);
        // classOrder 优先于名称提取
        ClassInfo ci = ClassInfo.builder().id(1L).name("高一9班").classOrder(2).build();
        assertEquals("2", numberRuleService.generateNumber(rule, athlete("高一", "M"), ci, 1));
    }

    @Test
    void preview_usesDefaultGradeMapping() {
        when(systemConfigRepository.findByConfigKey("number_rule")).thenReturn(Optional.empty());
        // 初一年级 -> 7（默认映射），班级名"初一2班" -> 02，seq=1 -> 01
        String num = numberRuleService.preview("{grade}{class}{seq:02d}", "初一年级", "初一2班", 1, true);
        assertEquals("70201", num);
    }
}
