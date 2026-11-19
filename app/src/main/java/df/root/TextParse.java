package df.root;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure parsers kept free of Android types so they can be unit-tested. */
final class TextParse {

    private static final Pattern PARCEL_WORD =
            Pattern.compile("([0-9a-fA-F]{8})\\s+([0-9a-fA-F]{8})");

    private TextParse() {
    }

    /** Second 32-bit word of the first parcel line, or -1. */
    static int taState(String parcel) {
        if (parcel == null) {
            return -1;
        }
        Matcher m = PARCEL_WORD.matcher(parcel);
        if (!m.find()) {
            return -1;
        }
        try {
            return (int) Long.parseLong(m.group(2), 16);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** knox.kg.state: 0 Prenormal, 1 Checking, 2 Active, 3 Locked, 4 Completed, 5 Error. */
    static boolean enrolled(int state) {
        return state == 2 || state == 3 || state == 4;
    }

    /**
     * The service writes the state NAME ("Active", "Locked", ...) into
     * knox.kg.state, not a number. Accept both shapes: older mirrors and log
     * snippets may carry the integer.
     */
    static int kgState(String value) {
        if (value == null) {
            return -1;
        }
        String v = value.trim();
        if (v.isEmpty()) {
            return -1;
        }
        try {
            return Integer.parseInt(v);
        } catch (Throwable ignored) {
        }
        switch (v.toLowerCase(java.util.Locale.US)) {
            case "prenormal": return 0;
            case "checking": return 1;
            case "active": return 2;
            case "locked": return 3;
            case "completed": return 4;
            case "error": return 5;
            default: return -1;
        }
    }

    static String kgStateName(int state) {
        switch (state) {
            case 0: return "Prenormal";
            case 1: return "Checking";
            case 2: return "Active";
            case 3: return "Locked";
            case 4: return "Completed";
            case 5: return "Error";
            default: return "Unknown";
        }
    }
}
