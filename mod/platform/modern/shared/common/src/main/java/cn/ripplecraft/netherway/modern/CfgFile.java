package cn.ripplecraft.netherway.modern;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Forge cfg 方言的自研解析器。1.13+ 的 Forge 删掉了 {@code Configuration}
 * （换成 TOML 的 ForgeConfigSpec），Fabric 没有内置配置——而本项目要求
 * 服主在 1.7.10 到 1.20.1 之间迁移时 {@code netherway.cfg} 与文档完全不变，
 * 所以照 core 手写 {@code Json} 的同一哲学，把用到的方言子集自己实现掉。
 *
 * <p>覆盖的子集：单层类目 {@code name { ... }}、带类型前缀的标量
 * {@code B:/S:/I:key=value}、字符串列表 {@code S:key <} 到单独一行的
 * {@code >}、{@code #} 注释行。不支持嵌套类目与带引号的键——本 mod 的
 * 配置从未用到。
 *
 * <p>与 ModConfigSelfTest 在 1.7.10/1.12.2 上钉死的语义逐条对齐：
 * <ul>
 * <li>未知键与未知类目原样保留，重写文件时一并写回；</li>
 * <li>坏标量值回退调用方默认值，且不触发回写（用户文件原样保留）；</li>
 * <li>只有「文件里缺了本次请求的键」才算 {@link #hasChanged()}，
 *     注释差异永远不算——服主手改的 cfg 绝不能被启动悄悄覆盖；</li>
 * <li>注释在保存时按当前 {@code L10n} 语言整体重生成（与 Forge 相同：
 *     手改注释能活下来靠的是「没有变更就不保存」）。</li>
 * </ul>
 */
public final class CfgFile {

    /** 一个已注册键：类型、值、注释与列表形态。 */
    private static final class Entry {
        final String type;          // "B" / "S" / "I"
        String value;               // 标量值；列表时为 null
        List<String> listValues;    // 列表值；标量时为 null
        String comment;             // 保存时写在键上方；可为 null
        boolean fromFile;           // 文件里本来就有

        Entry(String type) {
            this.type = type;
        }
    }

    private static final class Category {
        String comment;
        /** 已注册键，注册顺序即默认写出顺序。 */
        final Map<String, Entry> entries = new LinkedHashMap<String, Entry>();
        /** 文件里存在但代码从未请求的键/无法解析的行，原样保留。 */
        final List<String> unknownLines = new ArrayList<String>();
        List<String> propertyOrder;
        boolean fromFile;
    }

    private final Map<String, Category> categories = new LinkedHashMap<String, Category>();
    private boolean changed;

    /** 建一个空配置（文件缺失或解析失败时的退路）。 */
    public CfgFile() {
    }

    /**
     * 从文件解析。文件不存在等同于空配置；IO 失败与格式彻底无法辨认
     * 都抛出，由调用方决定 fail closed 的方式。
     */
    public CfgFile(Path file) throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        Category current = null;
        List<String> pendingList = null;
        String pendingListKey = null;
        for (String raw : lines) {
            String line = raw.trim();
            if (pendingList != null) {
                if (line.equals(">")) {
                    Entry e = new Entry("S");
                    e.listValues = pendingList;
                    e.fromFile = true;
                    current.entries.put(pendingListKey, e);
                    pendingList = null;
                    pendingListKey = null;
                } else {
                    pendingList.add(line);
                }
                continue;
            }
            if (line.isEmpty() || line.startsWith("#")) {
                continue; // 注释在保存时按当前语言重生成，与 Forge 行为一致
            }
            if (current == null) {
                if (line.endsWith("{")) {
                    String name = line.substring(0, line.length() - 1).trim();
                    current = categories.get(name);
                    if (current == null) {
                        current = new Category();
                        categories.put(name, current);
                    }
                    current.fromFile = true;
                    continue;
                }
                // 类目外的意外内容：整份文件的形状已不可信，交给上层 fail closed
                throw new IOException("cfg line outside category: " + line);
            }
            if (line.equals("}")) {
                current = null;
                continue;
            }
            int colon = line.indexOf(':');
            int eq = line.indexOf('=');
            if (line.endsWith("<") && colon > 0) {
                pendingListKey = line.substring(colon + 1, line.length() - 1).trim();
                pendingList = new ArrayList<String>();
                continue;
            }
            if (colon > 0 && eq > colon) {
                String type = line.substring(0, colon).trim();
                String key = line.substring(colon + 1, eq).trim();
                String value = line.substring(eq + 1);
                Entry e = new Entry(type);
                e.value = value;
                e.fromFile = true;
                current.entries.put(key, e);
                continue;
            }
            // 认不出的行按未知内容保留：宁可多留一行垃圾，不能弄丢服主的数据
            current.unknownLines.add(raw);
        }
        if (pendingList != null) {
            throw new IOException("cfg list not closed: " + pendingListKey);
        }
    }

    // ---- 读取（带注册语义：缺键补默认并标记待写） ----

    public String getString(String key, String category, String def, String comment) {
        Entry e = register(key, category, "S", comment);
        if (e.value == null) {
            e.value = def;
            return def;
        }
        return e.value.trim();
    }

    public boolean getBoolean(String key, String category, boolean def, String comment) {
        Entry e = register(key, category, "B", comment);
        if (e.value == null) {
            e.value = String.valueOf(def);
            return def;
        }
        String v = e.value.trim().toLowerCase(Locale.ROOT);
        if (v.equals("true")) {
            return true;
        }
        if (v.equals("false")) {
            return false;
        }
        return def; // 坏值回退默认，不改文件
    }

    public int getInt(String key, String category, int def, int min, int max, String comment) {
        Entry e = register(key, category, "I", comment);
        if (e.value == null) {
            e.value = String.valueOf(def);
            return def;
        }
        try {
            int v = Integer.parseInt(e.value.trim());
            return v < min ? min : (v > max ? max : v);
        } catch (NumberFormatException bad) {
            return def;
        }
    }

    public String[] getStringList(String key, String category, String[] def, String comment) {
        Category cat = category(category);
        Entry e = cat.entries.get(key);
        if (e == null) {
            e = new Entry("S");
            e.listValues = new ArrayList<String>(java.util.Arrays.asList(def));
            cat.entries.put(key, e);
            changed = true;
        } else if (e.listValues == null) {
            // 文件里是标量、代码要列表：按单元素列表读，不动文件
            List<String> one = new ArrayList<String>();
            if (e.value != null && !e.value.trim().isEmpty()) {
                one.add(e.value.trim());
            }
            e.comment = comment;
            return one.toArray(new String[0]);
        }
        e.comment = comment;
        return e.listValues.toArray(new String[0]);
    }

    public boolean hasKey(String category, String key) {
        Category cat = categories.get(category);
        return cat != null && cat.entries.containsKey(key) && cat.entries.get(key).fromFile;
    }

    public void setCategoryComment(String category, String comment) {
        category(category).comment = comment;
    }

    /** 保存时该类目按这个顺序写出；未列出与未知键跟在后面。 */
    public void setCategoryPropertyOrder(String category, List<String> order) {
        category(category).propertyOrder = new ArrayList<String>(order);
    }

    /** 只有「本次请求的键在文件里缺失」才为真；注释与坏值都不算。 */
    public boolean hasChanged() {
        return changed;
    }

    private Entry register(String key, String category, String type, String comment) {
        Category cat = category(category);
        Entry e = cat.entries.get(key);
        if (e == null) {
            e = new Entry(type);
            cat.entries.put(key, e);
            changed = true;
        }
        e.comment = comment;
        return e;
    }

    private Category category(String name) {
        Category cat = categories.get(name);
        if (cat == null) {
            cat = new Category();
            categories.put(name, cat);
        }
        return cat;
    }

    // ---- 保存 ----

    public void save(Path file) throws IOException {
        StringBuilder out = new StringBuilder();
        out.append("# Configuration file\n\n");
        for (Map.Entry<String, Category> c : categories.entrySet()) {
            Category cat = c.getValue();
            if (cat.comment != null && !cat.comment.isEmpty()) {
                for (String line : cat.comment.split("\n")) {
                    out.append("# ").append(line).append('\n');
                }
            }
            out.append(c.getKey()).append(" {\n");
            for (String key : orderedKeys(cat)) {
                Entry e = cat.entries.get(key);
                if (e == null) {
                    continue;
                }
                if (e.comment != null && !e.comment.isEmpty()) {
                    for (String line : e.comment.split("\n")) {
                        out.append("    # ").append(line).append('\n');
                    }
                }
                if (e.listValues != null) {
                    out.append("    ").append(e.type).append(':').append(key).append(" <\n");
                    for (String v : e.listValues) {
                        out.append("        ").append(v).append('\n');
                    }
                    out.append("     >\n");
                } else {
                    out.append("    ").append(e.type).append(':').append(key)
                            .append('=').append(e.value == null ? "" : e.value).append('\n');
                }
            }
            for (String raw : cat.unknownLines) {
                out.append(raw).append('\n');
            }
            out.append("}\n\n");
        }
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.write(file, out.toString().getBytes(StandardCharsets.UTF_8));
        changed = false;
    }

    private static List<String> orderedKeys(Category cat) {
        List<String> keys = new ArrayList<String>();
        if (cat.propertyOrder != null) {
            for (String k : cat.propertyOrder) {
                if (cat.entries.containsKey(k)) {
                    keys.add(k);
                }
            }
        }
        for (String k : cat.entries.keySet()) {
            if (!keys.contains(k)) {
                keys.add(k);
            }
        }
        return keys;
    }
}
