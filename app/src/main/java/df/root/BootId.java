package df.root;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Boot identity and monotonic uptime, readable without root or hidden APIs. */
final class BootId {

    private BootId() {
    }

    /** Stable for one boot, empty when unreadable. */
    static String current() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream("/proc/sys/kernel/random/boot_id"),
                StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            return line == null ? "" : line.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /** Seconds since boot, immune to wall-clock jumps. */
    static double uptime() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream("/proc/uptime"), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            if (line == null) {
                return -1;
            }
            String[] parts = line.trim().split("\\s+");
            return Double.parseDouble(parts[0]);
        } catch (Throwable t) {
            return -1;
        }
    }
}
