package com.sports.schedule.rule.inject.builtin;

import com.sports.schedule.rule.inject.RuleContext;
import com.sports.schedule.rule.inject.RuleOutcome;
import com.sports.schedule.rule.inject.RuleScript;
import com.sports.schedule.rule.inject.RuleScriptEngine;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内置「伪代码」规则片段引擎（形态一 · 零依赖 · 天然沙箱）。
 *
 * <p><b>语法</b>（每行一条规则，<code>#</code> 或 <code>//</code> 起注释）：</p>
 * <pre>
 *   when &lt;条件表达式&gt; then &lt;动作&gt;[, 或 ; 分隔的多个动作]
 *   if (&lt;条件表达式&gt;) { &lt;动作&gt;... }
 *
 *   条件：event.category == "径赛" &amp;&amp; athlete.sameClass
 *         lane &lt; 4 || batchSize &gt;= 6
 *   动作：hard += 100 | medium += 50 | soft += 20 | veto
 * </pre>
 *
 * <p>例：</p>
 * <pre>
 *   when event.category == "径赛" &amp;&amp; lane &lt;= 2 then soft += 30
 *   when teamMembers &gt; 1 &amp;&amp; heat &gt; 6 then veto
 * </pre>
 *
 * <p><b>安全</b>：表达式只能读 {@link RuleContext} 字段，动作只能改分数与否决标记——
 * 无法执行任意代码、无法触达 IO。执行异常一律降级为带 error 的 {@link RuleOutcome}。</p>
 */
public class BuiltinRuleScriptEngine implements RuleScriptEngine {

    /** 动作：hard/medium/soft += 数字。 */
    private static final Pattern ACTION =
            Pattern.compile("^(hard|medium|soft)\\s*\\+=\\s*(-?\\d+(?:\\.\\d+)?)$");

    @Override
    public String name() {
        return RuleScript.ENGINE_BUILTIN;
    }

    @Override
    public boolean available() {
        return true;
    }

    /**
     * 内置引擎可信：语法里<b>没有循环、没有跳转、没有 IO</b>，条件表达式是纯函数求值，
     * 输入规模与脚本长度同阶，不可能跑飞 → 直接在主线程求值，免去线程池与超时开销
     * （热路径逐落位调用，这层开销与「伪超时」风险都不该承担）。
     */
    @Override
    public boolean trusted() {
        return true;
    }

    @Override
    public RuleOutcome evaluate(RuleScript script, RuleContext ctx) {
        RuleOutcome out = RuleOutcome.empty();
        try {
            String src = stripComments(script == null ? null : script.source());
            int i = 0;
            int n = src.length();
            while (true) {
                i = skipWs(src, i);
                if (i >= n) {
                    break;
                }
                if (startsWithWord(src, i, "when")) {
                    i = spanWord(src, i, "when");
                    int thenIdx = findWord(src, i, "then");
                    if (thenIdx < 0) {
                        throw new IllegalArgumentException("when 语句缺少 then");
                    }
                    String cond = src.substring(i, thenIdx).trim();
                    int end = lineEnd(src, thenIdx + 4);
                    String actions = src.substring(thenIdx + 4, end);
                    apply(cond, actions, ctx, out, script);
                    i = end;
                } else if (startsWithWord(src, i, "if")) {
                    i = spanWord(src, i, "if");
                    i = skipWs(src, i);
                    if (i >= n || src.charAt(i) != '(') {
                        throw new IllegalArgumentException("if 后应为 (");
                    }
                    int close = matchParen(src, i);
                    String cond = src.substring(i + 1, close);
                    int j = skipWs(src, close + 1);
                    if (j >= n || src.charAt(j) != '{') {
                        throw new IllegalArgumentException("if(...) 后应为 {");
                    }
                    int closeBrace = matchBrace(src, j);
                    String actions = src.substring(j + 1, closeBrace);
                    apply(cond, actions, ctx, out, script);
                    i = closeBrace + 1;
                } else {
                    throw new IllegalArgumentException("无法识别的规则语句（应以 when 或 if 开头）: " + snippet(src, i));
                }
            }
        } catch (Exception e) {
            return RuleOutcome.error("内置规则执行失败: " + e.getMessage());
        }
        return out;
    }

    // ==================== 动作应用 ====================

    private void apply(String condSrc, String actionsSrc, RuleContext ctx, RuleOutcome out, RuleScript script) {
        if (!Expr.truthy(Expr.eval(Expr.parse(condSrc), ctx))) {
            return;
        }
        String ruleName = script == null || script.name() == null ? "规则" : script.name();
        for (String raw : actionsSrc.split("[;,]")) {
            String a = raw.trim();
            if (a.isEmpty()) {
                continue;
            }
            String low = a.toLowerCase();
            if ("veto".equals(low) || "reject".equals(low)) {
                out.markVeto();
                out.fire(ruleName + " → 否决（" + condSrc.trim() + "）");
                continue;
            }
            Matcher m = ACTION.matcher(a.replaceAll("\\s+", " ").trim());
            if (!m.matches()) {
                throw new IllegalArgumentException("无法识别的动作: '" + a + "'（应为 hard/medium/soft += 数字，或 veto）");
            }
            long val = Math.round(Double.parseDouble(m.group(2)));
            switch (m.group(1)) {
                case "hard" -> out.addHard(val);
                case "medium" -> out.addMedium(val);
                default -> out.addSoft(val);
            }
            out.fire(ruleName + " → " + a);
        }
    }

    // ==================== 文本工具 ====================

    /** 去掉 # 与 // 行注释（跳过引号内文本）。 */
    static String stripComments(String src) {
        if (src == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean inStr = false;
        char q = 0;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (inStr) {
                sb.append(c);
                if (c == '\\' && i + 1 < src.length()) {
                    sb.append(src.charAt(++i));
                } else if (c == q) {
                    inStr = false;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                inStr = true;
                q = c;
                sb.append(c);
                continue;
            }
            if (c == '#' || (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '/')) {
                while (i < src.length() && src.charAt(i) != '\n') {
                    i++;
                }
                if (i < src.length()) {
                    sb.append('\n');
                }
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static int skipWs(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    private static boolean startsWithWord(String s, int i, String word) {
        if (!s.regionMatches(true, i, word, 0, word.length())) {
            return false;
        }
        int after = i + word.length();
        return after >= s.length() || !Character.isLetterOrDigit(s.charAt(after));
    }

    private static int spanWord(String s, int i, String word) {
        return i + word.length();
    }

    /** 查找独立关键字（跳过引号内容），返回其起始下标；找不到返回 -1。 */
    private static int findWord(String s, int from, String word) {
        boolean inStr = false;
        char q = 0;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\') {
                    i++;
                } else if (c == q) {
                    inStr = false;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                inStr = true;
                q = c;
                continue;
            }
            if (c == '\n') {
                return -1; // 语句不跨行
            }
            if (s.regionMatches(true, i, word, 0, word.length())) {
                int before = i - 1;
                int after = i + word.length();
                boolean lb = before < 0 || !Character.isLetterOrDigit(s.charAt(before));
                boolean rb = after >= s.length() || !Character.isLetterOrDigit(s.charAt(after));
                if (lb && rb) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 当前行行尾（跳过引号），返回换行符下标或字符串长度。 */
    private static int lineEnd(String s, int from) {
        boolean inStr = false;
        char q = 0;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\') {
                    i++;
                } else if (c == q) {
                    inStr = false;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                inStr = true;
                q = c;
            } else if (c == '\n') {
                return i;
            }
        }
        return s.length();
    }

    private static int matchParen(String s, int open) {
        int depth = 0;
        boolean inStr = false;
        char q = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\') {
                    i++;
                } else if (c == q) {
                    inStr = false;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                inStr = true;
                q = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (--depth == 0) {
                    return i;
                }
            }
        }
        throw new IllegalArgumentException("括号未闭合");
    }

    private static int matchBrace(String s, int open) {
        int depth = 0;
        boolean inStr = false;
        char q = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\') {
                    i++;
                } else if (c == q) {
                    inStr = false;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                inStr = true;
                q = c;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                if (--depth == 0) {
                    return i;
                }
            }
        }
        throw new IllegalArgumentException("花括号未闭合");
    }

    private static String snippet(String s, int i) {
        int end = Math.min(s.length(), i + 20);
        return "'" + s.substring(i, end).replace("\n", " ") + (end < s.length() ? "…" : "") + "'";
    }
}
