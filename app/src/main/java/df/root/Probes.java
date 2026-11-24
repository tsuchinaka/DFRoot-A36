package df.root;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/** Read-only system property access without su and without hidden APIs. */
final class Probes {

    private Probes() {
    }

    static String prop(String name) {
        Stats.getprop();
        Process process = null;
        try {
            process = new ProcessBuilder("getprop", name).redirectErrorStream(true).start();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            reader.close();
            if (process.waitFor(2, TimeUnit.SECONDS)) {
                return line == null ? "" : line.trim();
            }
            process.destroyForcibly();
            return "";
        } catch (Throwable t) {
            return "";
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    static int intProp(String name, int fallback) {
        try {
            String value = prop(name);
            return value.isEmpty() ? fallback : Integer.parseInt(value);
        } catch (Throwable t) {
            return fallback;
        }
    }
}
