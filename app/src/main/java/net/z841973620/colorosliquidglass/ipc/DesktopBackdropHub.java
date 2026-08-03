package net.z841973620.colorosliquidglass.ipc;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.Choreographer;

import net.z841973620.colorosliquidglass.glass.DesktopBackdropSampler;

import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Launcher producer: while LIVE, continuously publishes the fixed max-menu region only.
 * Session OPEN carries LTRB once; no per-frame SystemUI requests.
 */
public final class DesktopBackdropHub {
    private static final String TAG = "ColorOSLiquidGlass";
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long WAIT_NS = 1_000_000L;
    private static final Object LATEST_LOCK = new Object();

    private static volatile SharedFrameArena arena;
    private static volatile Thread acceptThread;
    private static volatile Thread workerThread;
    private static HandlerThread publishThread;
    private static Handler publishHandler;

    private static final AtomicBoolean live = new AtomicBoolean(false);
    private static final AtomicBoolean frameCallbackPosted = new AtomicBoolean(false);
    private static final AtomicBoolean publishScheduled = new AtomicBoolean(false);
    private static final AtomicInteger lastServicedReq = new AtomicInteger(0);
    private static final AtomicInteger publishBusyIdx = new AtomicInteger(-1);
    private static final AtomicInteger lastPublishedGen = new AtomicInteger(Integer.MIN_VALUE);
    private static final Rect liveCrop = new Rect();

    private static Bitmap latestPlate;
    private static int latestIdx = -1;

    private static final Choreographer.FrameCallback FRAME_CB = frameTimeNanos -> {
        frameCallbackPosted.set(false);
        if (!STARTED.get() || !live.get()) return;
        SharedFrameArena ar = arena;
        if (ar == null) {
            postFrameCallback();
            return;
        }
        try {
            captureOnMain(ar);
        } catch (Throwable t) {
            Log.w(TAG, "live region capture failed", t);
        } finally {
            if (STARTED.get() && live.get()) postFrameCallback();
        }
    };

    private DesktopBackdropHub() {}

    public static void start() {
        if (!STARTED.compareAndSet(false, true)) return;
        try {
            arena = SharedFrameArena.createProducer();
        } catch (Throwable t) {
            STARTED.set(false);
            Log.e(TAG, "Ashmem SharedFrameArena create failed", t);
            return;
        }
        ensurePublishHandler();

        Thread accept = new Thread(DesktopBackdropHub::acceptLoop, "colg-ashmem-accept");
        accept.setDaemon(true);
        acceptThread = accept;
        accept.start();

        Thread worker = new Thread(DesktopBackdropHub::sessionLoop, "colg-ashmem-session");
        worker.setDaemon(true);
        worker.setPriority(Thread.NORM_PRIORITY);
        workerThread = worker;
        worker.start();
        Log.i(TAG, "DesktopBackdropHub ashmem v8 region ready futex=" + NativeFutex.available());
    }

    public static void stop() {
        STARTED.set(false);
        live.set(false);
        SharedFrameArena ar = arena;
        if (ar != null) {
            try {
                ar.publishSession(SharedFrameArena.FLAG_STOP);
            } catch (Throwable ignored) { }
        }
        Thread a = acceptThread;
        Thread w = workerThread;
        if (a != null) a.interrupt();
        if (w != null) w.interrupt();
        Handler ph = publishHandler;
        if (ph != null) ph.removeCallbacksAndMessages(null);
        HandlerThread pt = publishThread;
        if (pt != null) {
            try { pt.quitSafely(); } catch (Throwable ignored) { }
        }
        publishHandler = null;
        publishThread = null;
        arena = null;
        if (ar != null) ar.close();
        synchronized (LATEST_LOCK) {
            latestPlate = null;
            latestIdx = -1;
        }
        publishBusyIdx.set(-1);
        liveCrop.setEmpty();
        releasePlatesWhenIdle();
    }

    public static boolean isLive() {
        return live.get();
    }

    /**
     * Publish whether Launcher-backed ashmem is meaningful. PAGE_PREVIEW / TOGGLE_BAR clear
     * this so SystemUI float-menu glass falls back to {@code captureDisplay}. Home and
     * Overview/Recents both stay valid (icons vs Recents cards composited in Launcher).
     */
    public static void setDesktopHomeValid(boolean valid) {
        SharedFrameArena ar = arena;
        if (ar != null) {
            try {
                ar.setDesktopHomeValid(valid);
            } catch (Throwable ignored) { }
        }
    }

    public static boolean isDesktopHomeValid() {
        SharedFrameArena ar = arena;
        if (ar == null) return true;
        try {
            return ar.isDesktopHomeValid();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static void ensurePublishHandler() {
        if (publishHandler != null) return;
        synchronized (DesktopBackdropHub.class) {
            if (publishHandler != null) return;
            HandlerThread t = new HandlerThread("colg-ashmem-publish");
            t.start();
            publishThread = t;
            publishHandler = new Handler(t.getLooper());
        }
    }

    private static void acceptLoop() {
        while (STARTED.get()) {
            LocalServerSocket server = null;
            try {
                server = new LocalServerSocket(SharedFrameArena.SOCKET_NAME);
                while (STARTED.get()) {
                    LocalSocket client = null;
                    ParcelFileDescriptor send = null;
                    try {
                        client = server.accept();
                        SharedFrameArena ar = arena;
                        if (ar == null) continue;
                        send = ar.dupFd();
                        client.setFileDescriptorsForSend(new FileDescriptor[] {
                                send.getFileDescriptor()
                        });
                        DataOutputStream out = new DataOutputStream(client.getOutputStream());
                        out.writeInt(SharedFrameArena.MAGIC);
                        out.writeInt(SharedFrameArena.VERSION);
                        out.writeInt(ar.totalBytes());
                        out.writeInt(ar.bufWidth());
                        out.writeInt(ar.bufHeight());
                        out.flush();
                        Log.i(TAG, "handed ashmem FD v8 to SystemUI bytes=" + ar.totalBytes());
                    } catch (Throwable t) {
                        if (STARTED.get()) Log.w(TAG, "ashmem handshake failed", t);
                    } finally {
                        if (send != null) try { send.close(); } catch (Throwable ignored) { }
                        if (client != null) try { client.close(); } catch (Throwable ignored) { }
                    }
                }
            } catch (Throwable t) {
                if (STARTED.get()) Log.e(TAG, "acceptLoop restarting", t);
                try { Thread.sleep(250); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } finally {
                if (server != null) try { server.close(); } catch (Throwable ignored) { }
            }
        }
    }

    private static void sessionLoop() {
        while (STARTED.get()) {
            SharedFrameArena ar = arena;
            if (ar == null) {
                try { Thread.sleep(1); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }
            int req = ar.reqSeq();
            if (req != 0 && req != lastServicedReq.get()) {
                applySession(ar, req);
            }
            ar.waitForRequest(ar.reqSeq(), WAIT_NS);
        }
    }

    private static void applySession(SharedFrameArena ar, int req) {
        int flags = ar.reqFlags();
        lastServicedReq.set(req);
        if ((flags & SharedFrameArena.FLAG_STOP) != 0) {
            // Always tear down publish — even if live was already false (stale callbacks).
            live.set(false);
            Handler ph = publishHandler;
            if (ph != null) ph.removeCallbacksAndMessages(null);
            publishScheduled.set(false);
            synchronized (LATEST_LOCK) {
                latestPlate = null;
                latestIdx = -1;
            }
            publishBusyIdx.set(-1);
            lastPublishedGen.set(Integer.MIN_VALUE);
            liveCrop.setEmpty();
            MAIN.post(() -> DesktopBackdropSampler.setLiveCrop(new Rect()));
            releasePlatesWhenIdle();
            Log.i(TAG, "session STOP — halt region publish");
            return;
        }
        if ((flags & SharedFrameArena.FLAG_LIVE) != 0) {
            Rect crop = new Rect();
            ar.readRequest(crop);
            if (crop.isEmpty()) {
                Log.w(TAG, "LIVE without menu crop — ignored");
                return;
            }
            liveCrop.set(crop);
            lastPublishedGen.set(Integer.MIN_VALUE);
            MAIN.post(() -> DesktopBackdropSampler.setLiveCrop(crop));
            if (!live.getAndSet(true)) {
                Log.i(TAG, "session LIVE — region " + crop.width() + "x" + crop.height()
                        + " @ " + crop.toShortString());
            }
            ensurePublishHandler();
            MAIN.post(DesktopBackdropHub::postFrameCallback);
        }
    }

    private static void releasePlatesWhenIdle() {
        MAIN.post(new Runnable() {
            int tries;
            @Override public void run() {
                if (publishBusyIdx.get() >= 0 && tries++ < 30) {
                    MAIN.postDelayed(this, 16);
                    return;
                }
                DesktopBackdropSampler.releaseLiveBuffers();
            }
        });
    }

    private static void postFrameCallback() {
        if (!STARTED.get() || !live.get()) return;
        if (!frameCallbackPosted.compareAndSet(false, true)) return;
        try {
            Choreographer.getInstance().postFrameCallback(FRAME_CB);
        } catch (Throwable t) {
            frameCallbackPosted.set(false);
        }
    }

    private static void captureOnMain(SharedFrameArena ar) {
        // Pull latest glass rect written by SystemUI (may change every frame).
        Rect crop = new Rect();
        ar.readRequest(crop);
        if (crop.isEmpty()) {
            if (liveCrop.isEmpty()) return;
            crop.set(liveCrop);
        } else if (!crop.equals(liveCrop)) {
            liveCrop.set(crop);
            DesktopBackdropSampler.setLiveCrop(crop);
        }
        if (liveCrop.isEmpty()) return;
        int busy = publishBusyIdx.get();
        int genBefore = DesktopBackdropSampler.contentGeneration();
        Bitmap plate = DesktopBackdropSampler.captureLiveRegion(busy);
        if (plate == null || plate.isRecycled()) return;
        int gen = DesktopBackdropSampler.contentGeneration();
        // Pipe dedup: unchanged desktop → keep last ashmem frame (SysUI peeks).
        if (gen == genBefore && gen == lastPublishedGen.get() && ar.frameSeq() > 0) {
            return;
        }
        int idx = DesktopBackdropSampler.lastCaptureIndex();
        synchronized (LATEST_LOCK) {
            latestPlate = plate;
            latestIdx = idx;
        }
        lastPublishedGen.set(gen);
        schedulePublish(ar);
    }

    private static void schedulePublish(SharedFrameArena ar) {
        ensurePublishHandler();
        Handler ph = publishHandler;
        if (ph == null || ar == null) return;
        if (!publishScheduled.compareAndSet(false, true)) return;
        ph.post(() -> drainPublish(ar));
    }

    private static void drainPublish(SharedFrameArena ar) {
        try {
            while (STARTED.get() && live.get()) {
                Bitmap plate;
                int idx;
                synchronized (LATEST_LOCK) {
                    plate = latestPlate;
                    idx = latestIdx;
                    latestPlate = null;
                    latestIdx = -1;
                }
                if (plate == null || plate.isRecycled() || idx < 0) break;
                publishBusyIdx.set(idx);
                try {
                    ar.publishBitmap(plate);
                } finally {
                    publishBusyIdx.set(-1);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "drainPublish failed", t);
        } finally {
            publishScheduled.set(false);
            boolean hasLatest;
            synchronized (LATEST_LOCK) {
                hasLatest = latestPlate != null;
            }
            if (hasLatest && STARTED.get() && live.get()) {
                schedulePublish(ar);
            }
        }
    }
}
