package com.sports.schedule.rule.inject;

import com.sports.entity.SystemConfig;
import com.sports.repository.SystemConfigRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 规则脚本持久化测试：JSON 往返、脏配置容错（解析失败/缺失 → 空列表，不抛异常）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("规则脚本持久化")
class RuleScriptStoreTest {

    @Mock
    private SystemConfigRepository repository;

    @Test
    @DisplayName("保存 → 读回：字段完整往返")
    void saveThenLoadRoundTrip() {
        RuleScriptStore store = new RuleScriptStore(repository);
        when(repository.findByConfigKey(RuleScriptStore.CONFIG_KEY)).thenReturn(Optional.empty());
        ArgumentCaptor<SystemConfig> cap = ArgumentCaptor.forClass(SystemConfig.class);
        when(repository.save(any(SystemConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        store.save(List.of(new RuleScript("r1", "径赛前2道", "builtin", true,
                "when lane <= 2 then soft += 30")));
        verify(repository).save(cap.capture());
        String json = cap.getValue().getConfigValue();
        assertTrue(json.contains("r1"));
        assertTrue(json.contains("builtin"));

        // 用落库的 JSON 反读
        SystemConfig persisted = SystemConfig.builder()
                .configKey(RuleScriptStore.CONFIG_KEY).configValue(json).build();
        when(repository.findByConfigKey(RuleScriptStore.CONFIG_KEY)).thenReturn(Optional.of(persisted));

        List<RuleScript> loaded = store.load();
        assertEquals(1, loaded.size());
        assertEquals("r1", loaded.get(0).id());
        assertEquals("径赛前2道", loaded.get(0).name());
        assertEquals("builtin", loaded.get(0).engine());
        assertTrue(loaded.get(0).enabled());
        assertEquals("when lane <= 2 then soft += 30", loaded.get(0).source());
    }

    @Test
    @DisplayName("脏 JSON / 缺失配置 → 返回空列表（不抛异常）")
    void badOrMissingConfigYieldsEmpty() {
        when(repository.findByConfigKey(RuleScriptStore.CONFIG_KEY))
                .thenReturn(Optional.of(SystemConfig.builder()
                        .configKey(RuleScriptStore.CONFIG_KEY).configValue("{not-json").build()));
        assertTrue(new RuleScriptStore(repository).load().isEmpty());

        when(repository.findByConfigKey(RuleScriptStore.CONFIG_KEY)).thenReturn(Optional.empty());
        assertTrue(new RuleScriptStore(repository).load().isEmpty());
    }
}
