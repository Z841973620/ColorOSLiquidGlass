package net.z841973620.colorosliquidglass.ipc;

import android.util.Log;

/**
 * Cross-process futex on a word inside a shared {@code mmap} (Ashmem / SharedMemory) region.
 * Used to wake the producer/consumer without Binder or socket on the 120Hz path.
 */
public final class NativeFutex {
    private static final String TAG = "ColorOSLiquidGlass";
    private static final boolean AVAILABLE;

    static {
        boolean ok = false;
        try {
            System.loadLibrary("colg_shm");
            ok = true;
        } catch (Throwable t) {
            Log.w(TAG, "colg_shm futex unavailable; park spin fallback", t);
        }
        AVAILABLE = ok;
    }

    private NativeFutex() {}

    public static boolean available() {
        return AVAILABLE;
    }

    public static void wake(long baseAddress, int offsetBytes) {
        if (!AVAILABLE || baseAddress == 0) return;
        wakeNative(baseAddress, offsetBytes);
    }

    /** @return 0 on wake/spurious, errno otherwise (e.g. ETIMEDOUT) */
    public static int wait(long baseAddress, int offsetBytes, int expected, long timeoutNs) {
        if (!AVAILABLE || baseAddress == 0) return 0;
        return waitNative(baseAddress, offsetBytes, expected, timeoutNs);
    }

    private static native void wakeNative(long baseAddress, int offsetBytes);

    private static native int waitNative(long baseAddress, int offsetBytes, int expected,
            long timeoutNs);
}
