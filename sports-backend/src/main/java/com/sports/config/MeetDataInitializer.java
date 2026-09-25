package com.sports.config;

import com.sports.service.meet.MeetService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 默认届保障（幂等）。
 *
 * <p>历史库没有「届」概念，所有成绩 / 报名 / 出场都默认属于同一届。本类在启动时确保至少存在一个
 * active 的默认届，供 {@code Result / Registration / ParadeScore} 的历史数据回填（见
 * {@code MeetBackfillInitializer}）与后续新增数据归属使用。</p>
 *
 * <p>排在 {@code GradeNormalizeInitializer(@Order(30))} 之后即可——与年级归一化无依赖，
 * 但都应在 Hibernate 建表之后执行（CommandLineRunner 天然满足）。</p>
 */
@Slf4j
@Component
@Order(35)
@RequiredArgsConstructor
public class MeetDataInitializer implements CommandLineRunner {

    private final MeetService meetService;

    @Override
    public void run(String... args) {
        meetService.ensureDefaultMeet();
    }
}
