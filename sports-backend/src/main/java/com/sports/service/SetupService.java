package com.sports.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sports.common.BusinessException;
import com.sports.entity.SystemConfig;
import com.sports.entity.User;
import com.sports.repository.SystemConfigRepository;
import com.sports.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 建站向导服务 — 类似 WordPress / Discuz 的首次运行安装向导。
 *
 * 安全机制：
 *  - 安装完成后写入 data/installed.flag，此后所有安装接口一律拒绝（403），无法二次进入；
 *  - 安装判定 = installed.flag 存在 或 数据库已有用户（兼容旧版本升级）；
 *  - 安装接口完全公开（未安装时），但仅允许安装一次。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SetupService {

    private static final String FLAG_FILE = "./data/installed.flag";
    private static final String SETUP_CONFIG_FILE = "./data/setup-config.json";
    private static final String DB_CONFIG_FILE = "./data/db-config.json";

    private final UserRepository userRepository;
    private final SystemConfigRepository systemConfigRepository;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ==================== 安装状态 ====================

    /** 是否已安装（flag 存在或数据库已有用户） */
    @Transactional(readOnly = true)
    public boolean isInstalled() {
        if (new File(FLAG_FILE).exists()) return true;
        try {
            return userRepository.count() > 0;
        } catch (Exception e) {
            // 数据库尚未就绪时按未安装处理
            return false;
        }
    }

    /** 安装状态查询（前端据此决定是否进入向导） */
    public Map<String, Object> getStatus() {
        Map<String, Object> r = new LinkedHashMap<>();
        boolean installed = isInstalled();
        r.put("installed", installed);
        // 已安装时附带站点名
        if (installed) {
            r.put("siteName", readSiteName());
            r.put("dbType", detectCurrentDbType());
        }
        return r;
    }

    // ==================== 数据库连接测试 ====================

    public Map<String, Object> testDb(Map<String, Object> db) {
        String type = String.valueOf(db.getOrDefault("type", "sqlite")).toLowerCase();
        try (Connection conn = openConnection(db)) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("ok", true);
            r.put("message", "连接成功: " + conn.getMetaData().getDatabaseProductName()
                    + " " + conn.getMetaData().getDatabaseProductVersion());
            return r;
        } catch (Exception e) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("ok", false);
            r.put("message", "连接失败: " + e.getMessage());
            return r;
        }
    }

    // ==================== 执行安装 ====================

    @Transactional
    public Map<String, Object> install(Map<String, Object> body) {
        if (isInstalled()) {
            throw BusinessException.forbidden("系统已安装，安装向导已锁定");
        }

        String siteName = str(body.get("siteName"), "运动会智能编排系统");
        String siteDescription = str(body.get("siteDescription"), "");
        String adminUsername = str(body.get("adminUsername"), "admin");
        String adminPassword = str(body.get("adminPassword"), "");
        String dbType = String.valueOf(body.getOrDefault("dbType", "sqlite")).toLowerCase();

        if (adminUsername == null || adminUsername.trim().length() < 3) {
            throw new IllegalArgumentException("管理员用户名至少 3 个字符");
        }
        if (adminPassword == null || adminPassword.length() < 6) {
            throw new IllegalArgumentException("管理员密码至少 6 个字符");
        }
        if (!"sqlite".equals(dbType) && !"mysql".equals(dbType)) {
            throw new IllegalArgumentException("不支持的数据库类型: " + dbType);
        }

        // 1. 数据库配置：MySQL 场景写入 db-config.json（重启后自动连接）
        boolean needRestart = false;
        if ("mysql".equals(dbType)) {
            writeJsonFile(DB_CONFIG_FILE, body.get("db"));
            needRestart = true;
        }

        // 2. 写安装配置（站点 + 管理员），供重启后 DataInitializer 重建管理员/站点
        Map<String, Object> setupConfig = new LinkedHashMap<>();
        setupConfig.put("siteName", siteName);
        setupConfig.put("siteDescription", siteDescription);
        setupConfig.put("adminUsername", adminUsername.trim());
        setupConfig.put("adminPasswordHash", passwordEncoder.encode(adminPassword));
        writeJsonFile(SETUP_CONFIG_FILE, setupConfig);

        // 3. 在当前库创建管理员账号（SQLite 场景立即可用）
        createAdmin(adminUsername.trim(), adminPassword);

        // 4. 写站点名到当前库配置（SQLite 场景立即可见）
        saveSiteConfig(siteName, siteDescription);

        // 5. 写入安装标记（此后安装接口一律拒绝）
        writeFlag();

        log.info("建站向导安装完成: siteName={}, dbType={}, admin={}", siteName, dbType, adminUsername);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("installed", true);
        r.put("siteName", siteName);
        r.put("dbType", dbType);
        r.put("needRestart", needRestart);
        r.put("message", needRestart
                ? "安装完成，请重启应用以连接 MySQL 数据库"
                : "安装完成");
        return r;
    }

    // ==================== 供 DataInitializer 调用的重建逻辑 ====================

    /** 补写安装标记（升级兼容：数据库已有用户但无标记时，视为已安装） */
    public void markInstalled() {
        writeFlag();
    }

    /** 重启后读取安装配置，确保管理员账号与站点名存在（MySQL 场景在 MySQL 库重建） */
    @Transactional
    public void ensureInstalledData() {
        if (!new File(SETUP_CONFIG_FILE).exists()) return;
        try {
            Map<String, Object> cfg = objectMapper.readValue(new File(SETUP_CONFIG_FILE),
                    new TypeReference<Map<String, Object>>() {});
            String adminUsername = str(cfg.get("adminUsername"), "admin");
            String hash = str(cfg.get("adminPasswordHash"), null);
            String siteName = str(cfg.get("siteName"), null);
            String siteDescription = str(cfg.get("siteDescription"), null);

            // 管理员账号不存在则用预生成的 hash 创建
            if (hash != null && userRepository.findByUsername(adminUsername).isEmpty()) {
                User admin = User.builder()
                        .username(adminUsername)
                        .password(hash)
                        .role("ROLE_SUPER_ADMIN")
                        .name("系统管理员")
                        .status("active")
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build();
                userRepository.save(admin);
                log.info("从安装配置重建管理员: {}", adminUsername);
            }
            // 站点名配置
            if (siteName != null) {
                saveSiteConfig(siteName, siteDescription != null ? siteDescription : "");
            }
        } catch (Exception e) {
            log.warn("读取安装配置失败: {}", e.getMessage());
        }
    }

    // ==================== 辅助 ====================

    private void createAdmin(String username, String rawPassword) {
        if (userRepository.findByUsername(username).isEmpty()) {
            User admin = User.builder()
                    .username(username)
                    .password(passwordEncoder.encode(rawPassword))
                    .role("ROLE_SUPER_ADMIN")
                    .name("系统管理员")
                    .status("active")
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            userRepository.save(admin);
        }
    }

    private void saveSiteConfig(String name, String desc) {
        putConfig("siteName", name);
        putConfig("siteDescription", desc);
    }

    private void putConfig(String key, String val) {
        SystemConfig c = systemConfigRepository.findByConfigKey(key)
                .orElse(SystemConfig.builder().configKey(key).build());
        c.setConfigValue(val);
        c.setUpdatedAt(LocalDateTime.now());
        systemConfigRepository.save(c);
    }

    private String readSiteName() {
        return systemConfigRepository.findByConfigKey("siteName")
                .map(SystemConfig::getConfigValue)
                .orElse("运动会智能编排系统");
    }

    private String detectCurrentDbType() {
        try {
            File dbCfg = new File(DB_CONFIG_FILE);
            if (dbCfg.exists()) {
                Map<String, Object> c = objectMapper.readValue(dbCfg, new TypeReference<Map<String, Object>>() {});
                String t = String.valueOf(c.getOrDefault("type", "sqlite"));
                if ("mysql".equalsIgnoreCase(t)) return "mysql";
            }
        } catch (Exception ignored) {}
        return "sqlite";
    }

    private void writeFlag() {
        try {
            File f = new File(FLAG_FILE);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            f.createNewFile();
        } catch (Exception e) {
            throw new RuntimeException("写入安装标记失败: " + e.getMessage());
        }
    }

    private void writeJsonFile(String path, Object data) {
        try {
            File f = new File(path);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            objectMapper.writeValue(f, data);
        } catch (Exception e) {
            throw new RuntimeException("写入配置失败: " + e.getMessage());
        }
    }

    private Connection openConnection(Map<String, Object> db) throws Exception {
        String type = String.valueOf(db.getOrDefault("type", "sqlite")).toLowerCase();
        String url;
        String user = "";
        String pass = "";
        if ("mysql".equals(type)) {
            String host = str(db.get("host"), "localhost");
            String port = str(db.get("port"), "3306");
            String database = str(db.get("database"), "sports");
            user = str(db.get("username"), "root");
            pass = str(db.get("password"), "");
            url = "jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
            Class.forName("com.mysql.cj.jdbc.Driver");
        } else {
            String file = str(db.get("file"), "./sports_meet.db");
            url = "jdbc:sqlite:" + file;
            Class.forName("org.sqlite.JDBC");
        }
        return DriverManager.getConnection(url, user, pass);
    }

    private String str(Object v, String def) {
        return v != null && !String.valueOf(v).isBlank() ? String.valueOf(v) : def;
    }
}
