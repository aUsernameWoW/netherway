package cn.ripplecraft.netherway.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 只够用来解析 agent 状态输出的极简 JSON 读取器。
 *
 * <p>刻意不引入 Gson/Jackson：core 要保持零第三方依赖。Minecraft 自带 Gson，
 * 但依赖它就等于依赖 Minecraft，跨版本时反而是负担；而 1.7.10 的类路径上
 * 挤着几百个 mod，多一个库就多一分冲突风险。
 *
 * <p>只支持扁平对象、字符串与整数值——这正是 agent 输出的形状。
 * 遇到嵌套结构会抛异常而不是悄悄给出错误结果。
 *
 * <p>{@link #parseTopLevel} 是唯一的例外：它照收顶层标量、跳过嵌套值。
 * hasJoined 返回的 Profile 必带 {@code properties} 嵌套数组，而消费方
 * 只需要顶层的 {@code id}——为此引入完整解析器不值得。
 */
final class Json {

    private final String src;
    private int pos;

    private Json(String src) {
        this.src = src;
    }

    /** 解析一个扁平 JSON 对象；值统一以字符串形式返回。 */
    static Map<String, String> parseObject(String text) {
        return parse(text, true);
    }

    /**
     * 解析对象的顶层标量字段，嵌套的对象/数组值整个跳过（不进结果表）。
     * 供解析皮肤站 Profile 这类「只要顶层几个字段」的场景。
     */
    static Map<String, String> parseTopLevel(String text) {
        return parse(text, false);
    }

    private static Map<String, String> parse(String text, boolean strict) {
        Json p = new Json(text);
        p.skipWs();
        p.expect('{');
        Map<String, String> out = new LinkedHashMap<String, String>();
        p.skipWs();
        if (p.peek() == '}') {
            p.pos++;
            return out;
        }
        while (true) {
            p.skipWs();
            String key = p.readString();
            p.skipWs();
            p.expect(':');
            p.skipWs();
            String value = p.readValue(strict);
            if (value != null) {
                out.put(key, value);
            }
            p.skipWs();
            char c = p.next();
            if (c == '}') {
                return out;
            }
            if (c != ',') {
                throw new IllegalArgumentException("位置 " + (p.pos - 1) + " 处期望 , 或 }");
            }
        }
    }

    /** 读一个值；宽容模式下嵌套值被整个跳过并返回 null。 */
    private String readValue(boolean strict) {
        char c = peek();
        if (c == '"') {
            return readString();
        }
        if (c == '{' || c == '[') {
            if (strict) {
                throw new IllegalArgumentException("不支持嵌套结构，位置 " + pos);
            }
            skipNested();
            return null;
        }
        // 数字、true/false/null 一律按字面量读到分隔符为止
        int start = pos;
        while (pos < src.length()) {
            char ch = src.charAt(pos);
            if (ch == ',' || ch == '}' || ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') {
                break;
            }
            pos++;
        }
        if (start == pos) {
            throw new IllegalArgumentException("位置 " + pos + " 处是空值");
        }
        return src.substring(start, pos);
    }

    /**
     * 跳过一个完整的嵌套结构（当前位置是 {@code '{'} 或 {@code '['}）。
     * 只数括号配平、不校验括号种类是否匹配——这里是「跳过」不是「解析」，
     * 但字符串必须按规则整个吃掉，否则里面的括号会把计数搅乱。
     */
    private void skipNested() {
        int depth = 0;
        while (true) {
            if (pos >= src.length()) {
                throw new IllegalArgumentException("嵌套结构未闭合");
            }
            char c = src.charAt(pos);
            if (c == '"') {
                readString();
                continue;
            }
            pos++;
            if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
                if (depth == 0) {
                    return;
                }
            }
        }
    }

    private String readString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) {
                throw new IllegalArgumentException("字符串未闭合");
            }
            char c = src.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= src.length()) {
                throw new IllegalArgumentException("转义序列未闭合");
            }
            char esc = src.charAt(pos++);
            switch (esc) {
                case '"':  sb.append('"');  break;
                case '\\': sb.append('\\'); break;
                case '/':  sb.append('/');  break;
                case 'b':  sb.append('\b'); break;
                case 'f':  sb.append('\f'); break;
                case 'n':  sb.append('\n'); break;
                case 'r':  sb.append('\r'); break;
                case 't':  sb.append('\t'); break;
                case 'u':
                    // Go 的 encoding/json 会把 < > & 转成 < 之类，必须支持
                    if (pos + 4 > src.length()) {
                        throw new IllegalArgumentException("\\u 转义不完整");
                    }
                    sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                    pos += 4;
                    break;
                default:
                    throw new IllegalArgumentException("未知转义: \\" + esc);
            }
        }
    }

    private void skipWs() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return;
            }
            pos++;
        }
    }

    private char peek() {
        if (pos >= src.length()) {
            throw new IllegalArgumentException("内容意外结束");
        }
        return src.charAt(pos);
    }

    private char next() {
        char c = peek();
        pos++;
        return c;
    }

    private void expect(char expected) {
        char c = next();
        if (c != expected) {
            throw new IllegalArgumentException("位置 " + (pos - 1) + " 处期望 " + expected + "，实际是 " + c);
        }
    }
}
