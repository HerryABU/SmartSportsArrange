package com.sports.service;

import com.sports.entity.Arrangement;
import com.sports.repository.ArrangementRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归：二次编排后同一 事件×运动员 同时存在 预赛+决赛 两条编排，
 * findByEventIdAndAthleteId 单条 Optional 语义会触发 NonUniqueResultException
 * （冒烟实测：二次编排后成绩录入全部失败）。
 * 取数规则：优先决赛轮；无决赛时取 id 最大（最新）一条。
 */
class ArrangementRepositoryMultiRoundTest {

    private Arrangement row(long id, String round) {
        return Arrangement.builder().id(id).round(round).build();
    }

    @Test
    void multiRound_prefersFinalOverPreliminary() {
        ArrangementRepository repo = Mockito.mock(ArrangementRepository.class,
                Mockito.withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS));
        Mockito.when(repo.findByEventIdAndAthleteIdAllRounds(10L, 20L))
                .thenReturn(List.of(row(1L, "preliminary"), row(2L, "final")));

        Optional<Arrangement> picked = repo.findByEventIdAndAthleteId(10L, 20L);

        assertTrue(picked.isPresent());
        assertEquals("final", picked.get().getRound());
        assertEquals(2L, picked.get().getId());
    }

    @Test
    void multiRound_prelimOnly_returnsLatestById() {
        ArrangementRepository repo = Mockito.mock(ArrangementRepository.class,
                Mockito.withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS));
        Mockito.when(repo.findByEventIdAndAthleteIdAllRounds(10L, 20L))
                .thenReturn(List.of(row(1L, "preliminary"), row(9L, "preliminary")));

        Optional<Arrangement> picked = repo.findByEventIdAndAthleteId(10L, 20L);

        assertTrue(picked.isPresent());
        assertEquals(9L, picked.get().getId());
        assertEquals("preliminary", picked.get().getRound());
    }

    @Test
    void multiRound_emptyReturnsEmpty() {
        ArrangementRepository repo = Mockito.mock(ArrangementRepository.class,
                Mockito.withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS));
        Mockito.when(repo.findByEventIdAndAthleteIdAllRounds(10L, 20L))
                .thenReturn(List.of());

        assertTrue(repo.findByEventIdAndAthleteId(10L, 20L).isEmpty());
    }
}
