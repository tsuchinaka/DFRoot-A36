package df.root;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/** Bounded log-file helpers shared by the service and the ViewModel. */
final class LogFiles {

    private LogFiles() {
    }

    static synchronized void append(File file, byte[] bytes, long capBytes, long keepBytes) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                return;
            }
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write(bytes);
            }
            trim(file, capBytes, keepBytes);
        } catch (Throwable ignored) {
        }
    }

    static synchronized void trim(File file, long capBytes, long keepBytes) {
        if (!file.isFile() || file.length() <= capBytes) {
            return;
        }
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            long size = raf.length();
            long keep = Math.min(keepBytes, size);
            byte[] tail = new byte[(int) keep];
            raf.seek(size - keep);
            raf.readFully(tail);
            raf.setLength(0);
            raf.seek(0);
            raf.write(tail);
        } catch (Throwable ignored) {
        }
    }

    static synchronized String readTail(File file, int maxBytes) {
        if (!file.isFile() || maxBytes <= 0) {
            return "";
        }
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long size = raf.length();
            int keep = (int) Math.min((long) maxBytes, size);
            byte[] tail = new byte[keep];
            raf.seek(size - keep);
            raf.readFully(tail);
            return new String(tail, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }
}
