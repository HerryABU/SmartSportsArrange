package com.sports;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SportsApplication .env 解析纯函数测试：
 * parseEnvLines（词法解析）/ envKeyToProperty（键 → Spring 属性名映射）。
 */
class SportsApplicationEnvParseTest {

    @Test
    @DisplayName("解析 KEY=VALUE，跳过注释/空行/无等号行")
    void parsesPlainKeyValue() {
        Map<String, String> kv = SportsApplication.parseEnvLines(List.of(
                "# 注释行",
                "",
                "SPRING_DATASOURCE_USERNAME=root",
                "这是一行没有等号的内容",
                "SERVER_PORT=8899"
        ));
        assertEquals(2, kv.size());
        assertEquals("root", kv.get("SPRING_DATASOURCE_USERNAME"));
        assertEquals("8899", kv.get("SERVER_PORT"));
    }

    @Test
    @DisplayName("首行 UTF-8 BOM 被剔除，键名正常识别")
    void stripsBomOnFirstLine() {
        Map<String, String> kv = SportsApplication.parseEnvLines(List.of(
                "\uFEFFSERVER_PORT=8899"
        ));
        assertEquals("8899", kv.get("SERVER_PORT"));
    }

    @Test
    @DisplayName("支持 export 前缀（dotenv 习惯写法）")
    void supportsExportPrefix() {
        Map<String, String> kv = SportsApplication.parseEnvLines(List.of(
                "export SPRING_DATASOURCE_USERNAME=admin",
                "export  SERVER_PORT=9000"
        ));
        assertEquals("admin", kv.get("SPRING_DATASOURCE_USERNAME"));
        assertEquals("9000", kv.get("SERVER_PORT"));
    }

    @Test
    @DisplayName("值支持单双引号包裹与两侧空白")
    void unquotesAndTrims() {
        Map<String, String> kv = SportsApplication.parseEnvLines(List.of(
                "  SPRING_DATASOURCE_PASSWORD =  \"p@ss w0#d\"  ",
                "SPRING_DATASOURCE_USERNAME='admin user'",
                "SERVER_ADDRESS=0.0.0.0"
        ));
        assertEquals("p@ss w0#d", kv.get("SPRING_DATASOURCE_PASSWORD"));
        assertEquals("admin user", kv.get("SPRING_DATASOURCE_USERNAME"));
        assertEquals("0.0.0.0", kv.get("SERVER_ADDRESS"));
    }

    @Test
    @DisplayName("显式键映射为 Spring 短横线属性名")
    void explicitKeyMappings() {
        assertEquals("spring.datasource.url",
                SportsApplication.envKeyToProperty("SPRING_DATASOURCE_URL"));
        assertEquals("spring.datasource.driver-class-name",
                SportsApplication.envKeyToProperty("SPRING_DATASOURCE_DRIVER_CLASS_NAME"));
        assertEquals("spring.jpa.database-platform",
                SportsApplication.envKeyToProperty("SPRING_JPA_DATABASE_PLATFORM"));
        assertEquals("server.port", SportsApplication.envKeyToProperty("SERVER_PORT"));
        assertEquals("server.address", SportsApplication.envKeyToProperty("SERVER_ADDRESS"));
    }

    @Test
    @DisplayName("SPRING_ 前缀透传（下划线转点、转小写）；词内下划线键需显式 case，未知键忽略")
    void passthroughAndUnknownKeys() {
        // 透传是机械转换：下划线一律转点。词内下划线（如 SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE）
        // 会得到 spring.datasource.hikari.maximum.pool.size 而非 maximum-pool-size ——
        // 这类键必须显式 case（现有 5 个数据源键 + SERVER_* 已显式化），透传仅适合单词键。
        assertEquals("spring.jpa.show.sql",
                SportsApplication.envKeyToProperty("SPRING_JPA_SHOW_SQL"));
        assertNull(SportsApplication.envKeyToProperty("SOME_RANDOM_KEY"));
        assertNull(SportsApplication.envKeyToProperty("SERVER_TIMEOUT"));
    }

    @Test
    @DisplayName("重复键后值覆盖前值，插入顺序保持")
    void duplicatesKeepLastValueInOrder() {
        Map<String, String> kv = SportsApplication.parseEnvLines(List.of(
                "SERVER_PORT=8080",
                "SERVER_PORT=8899"
        ));
        assertEquals(1, kv.size());
        assertEquals("8899", kv.get("SERVER_PORT"));
        assertTrue(kv.keySet().iterator().next().equals("SERVER_PORT"));
    }
}
