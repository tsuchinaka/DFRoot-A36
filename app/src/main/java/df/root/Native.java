package df.root;

/**
 * JNI entry point. Kept out of the Activity so the chain never depends on a UI
 * class being retained, and so ProGuard only needs to pin this one class.
 */
public final class Native {

    static native int nativeRunAll(IReporter reporter, int encapPort, int spi,
                                   byte[] aesCbcKey, byte[] hmacKey, int icvLen,
                                   int senderFd, String ksudPath, boolean softReboot);

    static {
        System.loadLibrary("exp");
    }

    private Native() {
    }
}
