package cn.ripplecraft.netherway.core.telemetry;

/**
 * Low-cardinality environment dimensions shared by every batch.
 *
 * <p>Callers must pass build-version constants and normalized families, never user-controlled
 * strings. The constructor additionally applies a strict version grammar plus OS/architecture
 * allow-lists, so paths and raw system-property values cannot accidentally enter a payload.</p>
 */
public final class TelemetryEnvironment {

    public enum Role {
        CLIENT("client"), DEDICATED_SERVER("dedicated_server");

        private final String wire;

        Role(String wire) {
            this.wire = wire;
        }

        String wire() {
            return wire;
        }
    }

    private final String modVersion;
    private final String mcVersion;
    private final String javaMajor;
    private final String osFamily;
    private final String arch;
    private final Role role;

    public TelemetryEnvironment(String modVersion, String mcVersion, String javaMajor,
                                String osFamily, String arch, Role role) {
        this.modVersion = token(modVersion, 40);
        this.mcVersion = token(mcVersion, 24);
        this.javaMajor = numeric(javaMajor);
        this.osFamily = oneOf(osFamily, "windows", "macos", "linux");
        this.arch = oneOf(arch, "amd64", "arm64", "x86", "other");
        this.role = role == null ? Role.CLIENT : role;
    }

    private static String token(String value, int max) {
        if (value == null || value.isEmpty() || value.length() > max) {
            return "unknown";
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z')
                    && !(c >= '0' && c <= '9') && c != '.' && c != '-' && c != '_'
                    && c != '+') {
                return "unknown";
            }
        }
        return value;
    }

    private static String numeric(String value) {
        if (value == null || value.isEmpty() || value.length() > 3) {
            return "unknown";
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return "unknown";
            }
        }
        return value;
    }

    private static String oneOf(String value, String a, String b, String c) {
        return a.equals(value) || b.equals(value) || c.equals(value) ? value : "other";
    }

    private static String oneOf(String value, String a, String b, String c, String d) {
        return a.equals(value) || b.equals(value) || c.equals(value) || d.equals(value)
                ? value : "other";
    }

    String modVersion() { return modVersion; }
    String mcVersion() { return mcVersion; }
    String javaMajor() { return javaMajor; }
    String osFamily() { return osFamily; }
    String arch() { return arch; }
    Role role() { return role; }
}
