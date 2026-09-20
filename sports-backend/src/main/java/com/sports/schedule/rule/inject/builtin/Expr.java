package com.sports.schedule.rule.inject.builtin;

import com.sports.schedule.rule.inject.RuleContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 内置伪代码语言的「表达式」解析与求值（形态一 · 零依赖）。
 *
 * <p><b>沙箱</b>：表达式只能读取 {@link RuleContext} 里显式放入的字段，无法调用任意 Java 代码——
 * 这是内置路径相对 JSR-223 脚本注入的安全优势（后者需引擎级白名单）。</p>
 *
 * <p>支持：<code>|| &amp;&amp; !</code>、比较 <code>== != &lt; &lt;= &gt; &gt;=</code>、括号、
 * 数字 / 字符串 / 布尔字面量、点路径字段（如 <code>event.category</code>）。
 * 解析为 AST 后求值，确定性、无副作用。</p>
 */
public final class Expr {

    private Expr() {
    }

    // ==================== AST ====================

    public sealed interface Node permits Lit, Field, Not, Binary {
    }

    /** 字面量（数字为 Double，或 String / Boolean / null）。 */
    public record Lit(Object value) implements Node {
    }

    /** 点路径字段引用（如 event.category）。 */
    public record Field(String path) implements Node {
    }

    /** 逻辑非。 */
    public record Not(Node inner) implements Node {
    }

    /** 二元运算（&& || == != < <= > >=）。 */
    public record Binary(String op, Node left, Node right) implements Node {
    }

    // ==================== 解析 ====================

    public static Node parse(String src) {
        if (src == null || src.isBlank()) {
            throw new IllegalArgumentException("表达式为空");
        }
        Parser p = new Parser(tokenize(src));
        Node n = p.parseOr();
        p.expectEnd();
        return n;
    }

    // ==================== 求值 ====================

    public static Object eval(Node node, RuleContext ctx) {
        if (node instanceof Lit l) {
            return l.value();
        }
        if (node instanceof Field f) {
            return ctx == null ? null : ctx.get(f.path());
        }
        if (node instanceof Not nt) {
            return !truthy(eval(nt.inner(), ctx));
        }
        Binary b = (Binary) node;
        if ("&&".equals(b.op())) {
            return truthy(eval(b.left(), ctx)) && truthy(eval(b.right(), ctx));
        }
        if ("||".equals(b.op())) {
            return truthy(eval(b.left(), ctx)) || truthy(eval(b.right(), ctx));
        }
        Object l = eval(b.left(), ctx);
        Object r = eval(b.right(), ctx);
        return switch (b.op()) {
            case "==" -> equalsVal(l, r);
            case "!=" -> !equalsVal(l, r);
            case "<" -> cmp(l, r) < 0;
            case "<=" -> cmp(l, r) <= 0;
            case ">" -> cmp(l, r) > 0;
            case ">=" -> cmp(l, r) >= 0;
            default -> throw new IllegalStateException("未知运算符: " + b.op());
        };
    }

    /** 真值判定：null=false；Boolean 原样；数字 != 0；其余非空字符串即真。 */
    public static boolean truthy(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.doubleValue() != 0d;
        }
        return !String.valueOf(v).isEmpty();
    }

    private static boolean equalsVal(Object a, Object b) {
        Double na = num(a);
        Double nb = num(b);
        if (na != null && nb != null) {
            return na.doubleValue() == nb.doubleValue();
        }
        if (a instanceof Boolean || b instanceof Boolean) {
            return truthy(a) == truthy(b);
        }
        if (a == null || b == null) {
            return a == null && b == null;
        }
        return String.valueOf(a).equals(String.valueOf(b));
    }

    private static int cmp(Object a, Object b) {
        Double na = num(a);
        Double nb = num(b);
        if (na != null && nb != null) {
            return Double.compare(na, nb);
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    private static Double num(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    // ==================== Tokenizer ====================

    private record Tok(String type, String text) {
    }

    private static List<Tok> tokenize(String s) {
        List<Tok> out = new ArrayList<>();
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(s.charAt(i + 1)))) {
                int j = i;
                while (j < n && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '.')) {
                    j++;
                }
                out.add(new Tok("NUM", s.substring(i, j)));
                i = j;
                continue;
            }
            if (c == '"' || c == '\'') {
                char q = c;
                int j = i + 1;
                StringBuilder sb = new StringBuilder();
                while (j < n && s.charAt(j) != q) {
                    if (s.charAt(j) == '\\' && j + 1 < n) {
                        sb.append(s.charAt(j + 1));
                        j += 2;
                    } else {
                        sb.append(s.charAt(j++));
                    }
                }
                if (j >= n) {
                    throw new IllegalArgumentException("字符串未闭合");
                }
                out.add(new Tok("STR", sb.toString()));
                i = j + 1;
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_' || s.charAt(j) == '.')) {
                    j++;
                }
                out.add(new Tok("IDENT", s.substring(i, j)));
                i = j;
                continue;
            }
            String two = (i + 1 < n) ? s.substring(i, i + 2) : "";
            if ("==".equals(two) || "!=".equals(two) || "<=".equals(two)
                    || ">=".equals(two) || "&&".equals(two) || "||".equals(two)) {
                out.add(new Tok("OP", two));
                i += 2;
                continue;
            }
            if ("<>!()".indexOf(c) >= 0) {
                out.add(new Tok("OP", String.valueOf(c)));
                i++;
                continue;
            }
            throw new IllegalArgumentException("无法识别的字符: '" + c + "'");
        }
        out.add(new Tok("END", ""));
        return out;
    }

    // ==================== Parser（递归下降） ====================

    private static final class Parser {
        private final List<Tok> ts;
        private int p = 0;

        Parser(List<Tok> ts) {
            this.ts = ts;
        }

        private Tok peek() {
            return ts.get(p);
        }

        private boolean isOp(String op) {
            return "OP".equals(peek().type()) && peek().text().equals(op);
        }

        private void next() {
            p++;
        }

        private void expectOp(String op) {
            if (!isOp(op)) {
                throw new IllegalArgumentException("期望 '" + op + "'，实际 '" + peek().text() + "'");
            }
            p++;
        }

        void expectEnd() {
            if (!"END".equals(peek().type())) {
                throw new IllegalArgumentException("表达式尾部有多余内容: '" + peek().text() + "'");
            }
        }

        Node parseOr() {
            Node left = parseAnd();
            while (isOp("||")) {
                next();
                left = new Binary("||", left, parseAnd());
            }
            return left;
        }

        Node parseAnd() {
            Node left = parseNot();
            while (isOp("&&")) {
                next();
                left = new Binary("&&", left, parseNot());
            }
            return left;
        }

        Node parseNot() {
            if (isOp("!")) {
                next();
                return new Not(parseNot());
            }
            return parseCmp();
        }

        Node parseCmp() {
            Node left = parsePrimary();
            Tok t = peek();
            if ("OP".equals(t.type()) && List.of("==", "!=", "<", "<=", ">", ">=").contains(t.text())) {
                next();
                return new Binary(t.text(), left, parsePrimary());
            }
            return left;
        }

        Node parsePrimary() {
            Tok t = peek();
            if (isOp("(")) {
                next();
                Node inner = parseOr();
                expectOp(")");
                return inner;
            }
            if ("NUM".equals(t.type())) {
                next();
                return new Lit(Double.parseDouble(t.text()));
            }
            if ("STR".equals(t.type())) {
                next();
                return new Lit(t.text());
            }
            if ("IDENT".equals(t.type())) {
                next();
                String s = t.text();
                if ("true".equalsIgnoreCase(s)) {
                    return new Lit(Boolean.TRUE);
                }
                if ("false".equalsIgnoreCase(s)) {
                    return new Lit(Boolean.FALSE);
                }
                if ("null".equalsIgnoreCase(s) || "nil".equalsIgnoreCase(s)) {
                    return new Lit(null);
                }
                return new Field(s);
            }
            throw new IllegalArgumentException("表达式语法错误，意外 token: '" + t.text() + "'");
        }
    }
}
