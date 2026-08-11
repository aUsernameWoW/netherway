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
 */
final class Json {

    private final String src;
    private int pos;

    private Json(String src) {
        this.src = src;
    }

    /** 解析一个扁平 JSON 对象；值统一以字符串形式返回。 */
    static Map<String, String> parseObject(String text) {
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
            String value = p.readValue();
            out.put(key, value);
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

    /** 读一个值；嵌套对象/数组直接报错——agent 输出里不该有这些东西。 */
    private String readValue() {
        char c = peek();
        if (c == '"') {
            return readString();
        }
        if (c == '{' || c == '[') {
            throw new IllegalArgumentException("不支持嵌套结构，位置 " + pos);
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
