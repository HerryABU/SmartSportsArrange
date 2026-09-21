package com.sports.schedule.rule.inject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 规则脚本执行上下文（只读）。
 *
 * <p>以「点路径」取值（如 {@code event.category} / {@code athlete.className} / {@code lane}），
 * 脚本只能读取这里显式放入的字段，无法触达类加载器、反射或数据源——这是规则注入的安全边界之一。</p>
 */
public final class RuleContext {

    private final Map<String, Object> root;

    public RuleContext(Map<String, Object> root) {
        this.root = root == null ? Map.of() : root;
    }

    public static RuleContext of(Map<String, Object> root) {
        return new RuleContext(root);
    }

    /** 便捷构建器：链式 put。 */
    public static Builder builder() {
        return new Builder();
    }

    /** 点路径取值；任一段缺失返回 null（不抛异常，便于脚本写宽松条件）。 */
    public Object get(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        Object cur = root;
        for (String seg : path.split("\\.")) {
            if (cur instanceof Map<?, ?> m) {
                cur = m.get(seg);
            } else {
                return null;
            }
            if (cur == null) {
                return null;
            }
        }
        return cur;
    }

    public Map<String, Object> asMap() {
        return Collections.unmodifiableMap(root);
    }

    /** 上下文构建器。 */
    public static final class Builder {
        private final Map<String, Object> m = new LinkedHashMap<>();

        public Builder put(String key, Object value) {
            m.put(key, value);
            return this;
        }

        /** 放入一个嵌套对象（如 event / athlete）。 */
        public Builder putAll(Map<String, Object> values) {
            if (values != null) {
                m.putAll(values);
            }
            return this;
        }

        public RuleContext build() {
            return new RuleContext(m);
        }
    }
}
