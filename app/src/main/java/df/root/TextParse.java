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
}
