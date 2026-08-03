package net.z841973620.colorosliquidglass.ipc;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.util.Log;

import java.io.DataInputStream;
import java.io.FileDescriptor;
import java.util.concurrent.locks.LockSupport;

/**
 * SystemUI consumer (v8): handshake once, then OPEN with max menu rect / CLOSE.
 * Polls region-sized frames; no per-frame requests to Launcher.
 */
public final class DesktopBackdropClient {
    private static final String TAG = "ColorOSLiquidGlass";
    private static final Object LOCK = new Object();

    private static SharedFrameArena arena;
    private static int lastSeenFrameSeq;
    private static Bitmap reusable;
    private static volatile boolean warming;
    private static int sessionRefs;
    private static int sessionEpoch;
    private static final Rect sessionCrop = new Rect();

    private DesktopBackdropClient() {}

    /** Bumped on full session stop so delayed float-menu applies cannot reopen LIVE. */
    public static int sessionEpoch() {
        synchronized (LOCK) {
            return sessionEpoch;
        }
    }

    public static boolean isReady() {
        synchronized (LOCK) {
            return arena != null;
        }
    }

    public static boolean hasFrame() {
        synchronized (LOCK) {
            return arena != null && arena.frameSeq() > 0;
        }
    }

    /**
     * True when Launcher reports ashmem content is meaningful (home icons or Recents cards).
     * False in PAGE_PREVIEW / TOGGLE_BAR — prefer {@code captureDisplay}.
     */
    public static boolean isDesktopHomeValid() {
        synchronized (LOCK) {
            if (arena == null) return true;
            try {
                return arena.isDesktopHomeValid();
            } catch (Throwable ignored) {
                return true;
            }
        }
    }

    public static boolean isSharedFrame(Bitmap b) {
        synchronized (LOCK) {
            return b != null && b == reusable;
        }
    }

    public static void warmUp() {
        if (isReady() || warming) return;
        warming = true;
        Thread t = new Thread(() -> {
            try {
                synchronized (LOCK) {
                    ensureArenaLocked();
                }
            } finally {
                warming = false;
            }
        }, "colg-ashmem-warmup");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Menu opened — start LIVE. {@code menuOnScreen} is the initial glass rect; SysUI keeps
     * updating it via {@link #updateMenuRect} so Launcher tracks the real glass position.
     */
    public static void signalOpen(Rect menuOnScreen) {
        if (menuOnScreen == null || menuOnScreen.isEmpty()) {
            Log.w(TAG, "signalOpen ignored — empty menu rect");
            return;
        }
        synchronized (LOCK) {
            if (!ensureArenaLocked()) return;
            sessionRefs++;
            if (sessionRefs == 1) {
                sessionCrop.set(menuOnScreen);
                arena.publishSession(menuOnScreen.left, menuOnScreen.top,
                        menuOnScreen.right, menuOnScreen.bottom,
                        SharedFrameArena.FLAG_LIVE);
                Log.i(TAG, "signalOpen LIVE region " + menuOnScreen.toShortString());
            } else {
                updateMenuRectLocked(menuOnScreen);
            }
        }
    }

    /**
     * Hot path: write current glass screen rect into the arena (no futex / no reqSeq).
     * Launcher samples exactly this rect so SysUI can blit 1:1.
     */
    public static void updateMenuRect(Rect menuOnScreen) {
        if (menuOnScreen == null || menuOnScreen.isEmpty()) return;
        synchronized (LOCK) {
            if (arena == null || sessionRefs <= 0) return;
            updateMenuRectLocked(menuOnScreen);
        }
    }

    private static void updateMenuRectLocked(Rect menuOnScreen) {
        sessionCrop.set(menuOnScreen);
        arena.writeCrop(menuOnScreen.left, menuOnScreen.top,
                menuOnScreen.right, menuOnScreen.bottom);
    }

    public static void signalClose() {
        synchronized (LOCK) {
            if (arena == null) {
                sessionRefs = 0;
                sessionCrop.setEmpty();
                sessionEpoch++;
                return;
            }
            if (sessionRefs > 0) sessionRefs--;
            if (sessionRefs == 0) {
                sessionEpoch++;
                arena.publishSession(SharedFrameArena.FLAG_STOP);
                Log.i(TAG, "signalClose STOP epoch=" + sessionEpoch);
                if (reusable != null && !reusable.isRecycled()) {
                    try { reusable.recycle(); } catch (Throwable ignored) { }
                }
                reusable = null;
                lastSeenFrameSeq = 0;
                sessionCrop.setEmpty();
            }
        }
    }

    /**
     * Force Launcher to halt region publish and invalidate any in-flight OPEN from delayed
     * float-menu glass applies ({@code postDelayed} after dismiss).
     */
    public static void forceCloseSession() {
        synchronized (LOCK) {
            sessionRefs = 0;
            sessionEpoch++;
            sessionCrop.setEmpty();
            if (arena == null) return;
            try {
                arena.publishSession(SharedFrameArena.FLAG_STOP);
                Log.i(TAG, "forceCloseSession STOP epoch=" + sessionEpoch);
            } catch (Throwable t) {
                Log.w(TAG, "forceCloseSession failed", t);
            }
            if (reusable != null && !reusable.isRecycled()) {
                try { reusable.recycle(); } catch (Throwable ignored) { }
            }
            reusable = null;
            lastSeenFrameSeq = 0;
        }
    }

    public static Bitmap pollFrame() {
        synchronized (LOCK) {
            if (arena == null) return null;
            int seq = arena.frameSeq();
            if (seq == 0 || seq == lastSeenFrameSeq) return null;
            int w = arena.frameWidth();
            int h = arena.frameHeight();
            if (w <= 0 || h <= 0) return null;
            Bitmap dst = ensureReusableLocked(w, h);
            if (dst == null) return null;
            if (!arena.copyFrameInto(dst)) return null;
            lastSeenFrameSeq = seq;
            return dst;
        }
    }

    public static Bitmap peekFrame() {
        synchronized (LOCK) {
            if (arena == null || arena.frameSeq() == 0) return null;
            if (reusable != null && !reusable.isRecycled()
                    && reusable.getWidth() == arena.frameWidth()
                    && reusable.getHeight() == arena.frameHeight()
                    && lastSeenFrameSeq == arena.frameSeq()) {
                return reusable;
            }
            return pollFrame();
        }
    }

    public static Bitmap awaitFirstFrame(long timeoutMs) {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        Bitmap frame;
        do {
            frame = pollFrame();
            if (frame != null) return frame;
            SharedFrameArena ar;
            synchronized (LOCK) { ar = arena; }
            if (ar != null) {
                ar.waitForFrame(ar.frameSeq(), 200_000L);
            } else {
                LockSupport.parkNanos(50_000L);
            }
        } while (System.nanoTime() < deadline);
        return peekFrame();
    }

    private static Bitmap ensureReusableLocked(int w, int h) {
        if (reusable != null && !reusable.isRecycled()
                && reusable.getWidth() == w && reusable.getHeight() == h) {
            return reusable;
        }
        if (reusable != null && !reusable.isRecycled()) {
            try { reusable.recycle(); } catch (Throwable ignored) { }
        }
        reusable = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        return reusable;
    }

    private static void resetLocked() {
        if (reusable != null && !reusable.isRecycled()) {
            try { reusable.recycle(); } catch (Throwable ignored) { }
        }
        if (arena != null) {
            try { arena.close(); } catch (Throwable ignored) { }
        }
        arena = null;
        reusable = null;
        lastSeenFrameSeq = 0;
        sessionRefs = 0;
        sessionEpoch++;
        sessionCrop.setEmpty();
    }

    private static boolean ensureArenaLocked() {
        if (arena != null) return true;
        LocalSocket socket = null;
        FileDescriptor[] fds = null;
        try {
            socket = new LocalSocket();
            socket.connect(new LocalSocketAddress(
                    SharedFrameArena.SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT));
            socket.setSoTimeout(2000);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            int magic = in.readInt();
            int version = in.readInt();
            int total = in.readInt();
            int bufW = in.readInt();
            int bufH = in.readInt();
            fds = socket.getAncillaryFileDescriptors();
            if (magic != SharedFrameArena.MAGIC || version != SharedFrameArena.VERSION) {
                Log.w(TAG, "ashmem handshake mismatch got=" + version
                        + " want=" + SharedFrameArena.VERSION);
                return false;
            }
            if (fds == null || fds.length == 0) {
                Log.w(TAG, "ashmem handshake missing FD");
                return false;
            }
            ParcelFileDescriptor pfd = ParcelFileDescriptor.dup(fds[0]);
            arena = SharedFrameArena.fromFileDescriptor(pfd, total, bufW, bufH);
            lastSeenFrameSeq = arena.frameSeq();
            Log.i(TAG, "mapped ashmem arena v8 " + bufW + "x" + bufH);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "ashmem handshake failed", t);
            resetLocked();
            return false;
        } finally {
            closeFileDescriptors(fds);
            if (socket != null) try { socket.close(); } catch (Throwable ignored) { }
        }
    }

    private static void closeFileDescriptors(FileDescriptor[] fds) {
        if (fds == null) return;
        for (FileDescriptor fd : fds) {
            if (fd == null || !fd.valid()) continue;
            try { Os.close(fd); } catch (Throwable ignored) { }
        }
    }
}
