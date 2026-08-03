package net.z841973620.colorosliquidglass.ipc;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.os.ParcelFileDescriptor;
import android.os.SharedMemory;
import android.system.ErrnoException;
import android.util.DisplayMetrics;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Ashmem / {@link SharedMemory} triple-buffer frame arena (true cross-process mmap).
 * <p>
 * v8 session model: SystemUI sends one OPEN with the max menu screen rect ({@link #FLAG_LIVE}
 * + LTRB) and CLOSE ({@link #FLAG_STOP}). Launcher continuously publishes only that region
 * (not full-screen). No per-frame requests.
 */
public final class SharedFrameArena {
    private static final String TAG = "ColorOSLiquidGlass";

    public static final String SOCKET_NAME = "colg_ashmem_v8";
    public static final int MAGIC = 0x434F4C47;
    /** v8: LIVE carries max menu LTRB; Launcher publishes that region only. */
    public static final int VERSION = 8;
    public static final int SLOTS = 3;
    public static final int HEADER_SIZE = 4096;

    public static final int OFF_MAGIC = 0;
    public static final int OFF_VERSION = 4;
    public static final int OFF_TOTAL_BYTES = 8;
    public static final int OFF_REQ_SEQ = 12;
    public static final int OFF_REQ_L = 16;
    public static final int OFF_REQ_T = 20;
    public static final int OFF_REQ_R = 24;
    public static final int OFF_REQ_B = 28;
    public static final int OFF_REQ_FLAGS = 32;
    public static final int OFF_REQ_BLUR_MX = 36;
    public static final int OFF_FRAME_SEQ = 40;
    public static final int OFF_FRAME_W = 44;
    public static final int OFF_FRAME_H = 48;
    public static final int OFF_READY_SLOT = 52;
    public static final int OFF_WRITE_SLOT = 56;
    public static final int OFF_BUF_W = 60;
    public static final int OFF_BUF_H = 64;
    public static final int OFF_SLOT_BYTES = 68;
    public static final int OFF_REQ_FUTEX = 72;
    public static final int OFF_FRAME_FUTEX = 76;
    /** 1 = Launcher ashmem meaningful (home or Overview); 0 = prefer captureDisplay. */
    public static final int OFF_DESKTOP_HOME = 80;

    public static final int FLAG_FORCE = 1;
    public static final int FLAG_STOP = 2;
    public static final int FLAG_LIVE = 4;

    private final SharedMemory sharedMemory;
    private final ByteBuffer map;
    private final long baseAddress;
    private final int totalBytes;
    private final int bufW;
    private final int bufH;
    private final int slotBytes;

    private SharedFrameArena(SharedMemory sharedMemory, ByteBuffer map, long baseAddress,
            int totalBytes, int bufW, int bufH, int slotBytes) {
        this.sharedMemory = sharedMemory;
        this.map = map;
        this.baseAddress = baseAddress;
        this.totalBytes = totalBytes;
        this.bufW = bufW;
        this.bufH = bufH;
        this.slotBytes = slotBytes;
    }

    public static SharedFrameArena createProducer() throws IOException {
        DisplayMetrics dm = Resources.getSystem().getDisplayMetrics();
        int bufW = Math.max(1, dm.widthPixels);
        int bufH = Math.max(1, dm.heightPixels);
        // Allow either orientation without realloc.
        int maxEdge = Math.max(bufW, bufH);
        bufW = maxEdge;
        bufH = maxEdge;
        int slotBytes = bufW * bufH * 4;
        int total = HEADER_SIZE + slotBytes * SLOTS;
        SharedMemory shm = null;
        try {
            shm = SharedMemory.create("colg_ashmem_v8", total);
            ByteBuffer map = shm.mapReadWrite();
            map.order(ByteOrder.nativeOrder());
            SharedFrameArena arena = new SharedFrameArena(
                    shm, map, directAddress(map), total, bufW, bufH, slotBytes);
            arena.writeHeaderNew();
            shm = null;
            return arena;
        } catch (ErrnoException e) {
            throw new IOException("SharedMemory(ashmem) create failed", e);
        } finally {
            if (shm != null) try { shm.close(); } catch (Throwable ignored) { }
        }
    }

    public static SharedFrameArena fromFileDescriptor(ParcelFileDescriptor pfd,
            int expectedTotal, int bufW, int bufH) throws IOException {
        if (pfd == null) throw new IOException("null ashmem fd");
        ParcelFileDescriptor owned = pfd;
        SharedMemory shm = null;
        try {
            shm = SharedMemory.fromFileDescriptor(owned);
            owned = null;
            ByteBuffer map;
            try {
                map = shm.mapReadWrite();
            } catch (ErrnoException e) {
                throw new IOException("ashmem mmap failed", e);
            }
            map.order(ByteOrder.nativeOrder());
            if (map.getInt(OFF_MAGIC) != MAGIC || map.getInt(OFF_VERSION) != VERSION) {
                throw new IOException("ashmem magic/version mismatch");
            }
            int total = map.getInt(OFF_TOTAL_BYTES);
            int slotBytes = map.getInt(OFF_SLOT_BYTES);
            int bw = map.getInt(OFF_BUF_W);
            int bh = map.getInt(OFF_BUF_H);
            if (bw <= 0) bw = bufW;
            if (bh <= 0) bh = bufH;
            if (slotBytes <= 0) slotBytes = bw * bh * 4;
            if (expectedTotal > 0 && total != expectedTotal) {
                Log.w(TAG, "total bytes mismatch header=" + total + " handshake=" + expectedTotal);
            }
            SharedFrameArena arena = new SharedFrameArena(
                    shm, map, directAddress(map), total, bw, bh, slotBytes);
            shm = null;
            return arena;
        } finally {
            if (shm != null) try { shm.close(); } catch (Throwable ignored) { }
            if (owned != null) try { owned.close(); } catch (Throwable ignored) { }
        }
    }

    public ParcelFileDescriptor dupFd() throws IOException {
        return ParcelFileDescriptor.dup(sharedMemoryFd(sharedMemory));
    }

    public long baseAddress() { return baseAddress; }
    public int totalBytes() { return totalBytes; }
    public int bufWidth() { return bufW; }
    public int bufHeight() { return bufH; }
    public int slotBytes() { return slotBytes; }

    private void writeHeaderNew() {
        for (int i = 0; i < HEADER_SIZE; i += 4) map.putInt(i, 0);
        map.putInt(OFF_MAGIC, MAGIC);
        map.putInt(OFF_VERSION, VERSION);
        map.putInt(OFF_TOTAL_BYTES, totalBytes);
        map.putInt(OFF_BUF_W, bufW);
        map.putInt(OFF_BUF_H, bufH);
        map.putInt(OFF_SLOT_BYTES, slotBytes);
        map.putInt(OFF_DESKTOP_HOME, 1);
        storeStoreFence();
    }

    /** Session open/close (or legacy crop request). Bumps {@code reqSeq} and wakes Launcher. */
    public void publishRequest(int left, int top, int right, int bottom, int flags, float blurPx) {
        map.putInt(OFF_REQ_L, left);
        map.putInt(OFF_REQ_T, top);
        map.putInt(OFF_REQ_R, right);
        map.putInt(OFF_REQ_B, bottom);
        map.putInt(OFF_REQ_FLAGS, flags);
        map.putInt(OFF_REQ_BLUR_MX, Math.round(blurPx * 1000f));
        storeStoreFence();
        int next = map.getInt(OFF_REQ_SEQ) + 1;
        map.putInt(OFF_REQ_SEQ, next);
        map.putInt(OFF_REQ_FUTEX, next);
        storeStoreFence();
        NativeFutex.wake(baseAddress, OFF_REQ_FUTEX);
    }

    /** Session open with fixed max menu rect, or close ({@link #FLAG_STOP}). */
    public void publishSession(int left, int top, int right, int bottom, int flags) {
        publishRequest(left, top, right, bottom, flags, 0f);
    }

    /** Close / stop without a crop. */
    public void publishSession(int flags) {
        publishRequest(0, 0, 0, 0, flags, 0f);
    }

    /**
     * Live position sync: update LTRB without bumping {@code reqSeq} / futex.
     * Launcher reads this every vsync while LIVE so the sample tracks the glass.
     */
    public void writeCrop(int left, int top, int right, int bottom) {
        map.putInt(OFF_REQ_L, left);
        map.putInt(OFF_REQ_T, top);
        map.putInt(OFF_REQ_R, right);
        map.putInt(OFF_REQ_B, bottom);
        storeStoreFence();
    }

    /**
     * Launcher → SystemUI: whether ashmem composite is meaningful (home icons or Recents).
     * 0 in PAGE_PREVIEW / TOGGLE_BAR so SysUI should prefer {@code captureDisplay}.
     */
    public void setDesktopHomeValid(boolean valid) {
        map.putInt(OFF_DESKTOP_HOME, valid ? 1 : 0);
        storeStoreFence();
    }

    public boolean isDesktopHomeValid() {
        return map.getInt(OFF_DESKTOP_HOME) != 0;
    }

    public int reqSeq() { return map.getInt(OFF_REQ_SEQ); }
    public int frameSeq() { return map.getInt(OFF_FRAME_SEQ); }
    public int frameWidth() { return map.getInt(OFF_FRAME_W); }
    public int frameHeight() { return map.getInt(OFF_FRAME_H); }
    public int readySlot() { return map.getInt(OFF_READY_SLOT); }
    public int writeSlot() { return map.getInt(OFF_WRITE_SLOT); }
    public int reqFlags() { return map.getInt(OFF_REQ_FLAGS); }
    public float reqBlurPx() { return map.getInt(OFF_REQ_BLUR_MX) / 1000f; }

    public void readRequest(android.graphics.Rect out) {
        out.set(map.getInt(OFF_REQ_L), map.getInt(OFF_REQ_T),
                map.getInt(OFF_REQ_R), map.getInt(OFF_REQ_B));
    }

    public void waitForRequest(int expected, long timeoutNs) {
        if (baseAddress != 0 && NativeFutex.available()) {
            NativeFutex.wait(baseAddress, OFF_REQ_FUTEX, expected, timeoutNs);
        } else {
            java.util.concurrent.locks.LockSupport.parkNanos(timeoutNs > 0 ? timeoutNs : 200_000L);
        }
    }

    public void waitForFrame(int expected, long timeoutNs) {
        if (baseAddress != 0 && NativeFutex.available()) {
            NativeFutex.wait(baseAddress, OFF_FRAME_FUTEX, expected, timeoutNs);
        } else {
            java.util.concurrent.locks.LockSupport.parkNanos(timeoutNs > 0 ? timeoutNs : 200_000L);
        }
    }

    /**
     * Single blit of an ARGB_8888 software bitmap into the next free Ashmem slot, then publish.
     * Never downscales (full-resolution requirement). Returns false if the frame does not fit.
     */
    public boolean publishBitmap(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return false;
        if (bitmap.getConfig() == Bitmap.Config.HARDWARE) {
            Log.w(TAG, "reject HARDWARE bitmap for ashmem publish");
            return false;
        }
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        if (w <= 0 || h <= 0) return false;
        int bytes = w * h * 4;
        if (bytes > slotBytes) {
            Log.w(TAG, "frame " + w + "x" + h + " exceeds ashmem slot " + bufW + "x" + bufH);
            return false;
        }
        int ready = readySlot();
        int write = (ready + 1) % SLOTS;
        int offset = HEADER_SIZE + write * slotBytes;
        ByteBuffer pixels = map.duplicate();
        pixels.order(ByteOrder.nativeOrder());
        pixels.position(offset);
        pixels.limit(offset + bytes);
        ByteBuffer slice = pixels.slice();
        slice.order(ByteOrder.nativeOrder());
        try {
            bitmap.copyPixelsToBuffer(slice);
        } catch (Throwable t) {
            Log.w(TAG, "copyPixelsToBuffer failed", t);
            return false;
        }
        storeStoreFence();
        map.putInt(OFF_FRAME_W, w);
        map.putInt(OFF_FRAME_H, h);
        map.putInt(OFF_READY_SLOT, write);
        map.putInt(OFF_WRITE_SLOT, (write + 1) % SLOTS);
        storeStoreFence();
        int next = map.getInt(OFF_FRAME_SEQ) + 1;
        map.putInt(OFF_FRAME_SEQ, next);
        map.putInt(OFF_FRAME_FUTEX, next);
        storeStoreFence();
        NativeFutex.wake(baseAddress, OFF_FRAME_FUTEX);
        return true;
    }

    /** Copy the published ready slot into {@code dst} (must already be WxH ARGB_8888). */
    public boolean copyFrameInto(Bitmap dst) {
        if (dst == null || dst.isRecycled()) return false;
        int seq = frameSeq();
        loadLoadFence();
        int w = frameWidth();
        int h = frameHeight();
        int slot = readySlot();
        if (w <= 0 || h <= 0 || slot < 0 || slot >= SLOTS) return false;
        if (dst.getWidth() != w || dst.getHeight() != h) return false;
        int bytes = w * h * 4;
        if (bytes > slotBytes) return false;
        int offset = HEADER_SIZE + slot * slotBytes;
        ByteBuffer pixels = map.duplicate();
        pixels.order(ByteOrder.nativeOrder());
        pixels.position(offset);
        pixels.limit(offset + bytes);
        ByteBuffer slice = pixels.slice();
        slice.order(ByteOrder.nativeOrder());
        try {
            dst.copyPixelsFromBuffer(slice);
        } catch (Throwable t) {
            Log.w(TAG, "copyPixelsFromBuffer failed", t);
            return false;
        }
        loadLoadFence();
        return frameSeq() == seq && readySlot() == slot;
    }

    public void close() {
        try { SharedMemory.unmap(map); } catch (Throwable ignored) { }
        try { sharedMemory.close(); } catch (Throwable ignored) { }
    }

    private static long directAddress(ByteBuffer map) {
        try {
            Field f = java.nio.Buffer.class.getDeclaredField("address");
            f.setAccessible(true);
            long addr = f.getLong(map);
            if (addr != 0L) return addr;
        } catch (Throwable t) {
            Log.w(TAG, "direct buffer address unavailable; futex disabled", t);
        }
        return 0L;
    }

    private static FileDescriptor sharedMemoryFd(SharedMemory shm) throws IOException {
        try {
            Method m = SharedMemory.class.getDeclaredMethod("getFileDescriptor");
            m.setAccessible(true);
            Object fd = m.invoke(shm);
            if (fd instanceof FileDescriptor) return (FileDescriptor) fd;
        } catch (Throwable t) {
            throw new IOException("SharedMemory.getFileDescriptor unavailable", t);
        }
        throw new IOException("SharedMemory.getFileDescriptor returned null");
    }

    private static void storeStoreFence() { VarHandle.storeStoreFence(); }
    private static void loadLoadFence() { VarHandle.loadLoadFence(); }
}
