package net.z841973620.colorosliquidglass.glass;

import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.HardwareRenderer;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.util.Log;

/**
 * Full-resolution blur via GPU {@link RenderEffect} + {@link HardwareRenderer}.
 * Falls back to {@link BackdropBlur} (CPU stack-blur) when RenderEffect is unavailable.
 */
public final class HardwareBlur {
    private static final String TAG = "ColorOSLiquidGlass";
    private static final Paint BITMAP_PAINT = new Paint(Paint.FILTER_BITMAP_FLAG);

    private HardwareBlur() {}

    /**
     * @param src full-resolution source (HARDWARE or software)
     * @param radiusPx blur radius in device pixels
     * @return blurred bitmap (prefer HARDWARE when possible); may return {@code src} if radius&lt;1
     */
    public static Bitmap blur(Bitmap src, float radiusPx) {
        if (src == null || src.isRecycled()) return src;
        int radius = Math.round(radiusPx);
        if (radius < 1) return src;
        radius = Math.min(radius, 64);

        if (Build.VERSION.SDK_INT >= 31) {
            Bitmap gpu = blurGpu(src, radius);
            if (gpu != null && !gpu.isRecycled()) return gpu;
        }
        return BackdropBlur.blur(src, radiusPx);
    }

    private static Bitmap blurGpu(Bitmap src, int radius) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= 0 || h <= 0) return null;
        ImageReader reader = null;
        HardwareRenderer renderer = null;
        try {
            RenderEffect effect = RenderEffect.createBlurEffect(
                    radius, radius, Shader.TileMode.CLAMP);
            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 1,
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
                            | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
                            | HardwareBuffer.USAGE_CPU_READ_OFTEN);
            renderer = new HardwareRenderer();
            RenderNode node = new RenderNode("ColorOSLiquidGlassBlur");
            node.setPosition(0, 0, w, h);
            node.setRenderEffect(effect);
            RecordingCanvas canvas = node.beginRecording(w, h);
            try {
                canvas.drawBitmap(src, 0f, 0f, BITMAP_PAINT);
            } finally {
                node.endRecording();
            }
            renderer.setContentRoot(node);
            renderer.setSurface(reader.getSurface());
            int sync = renderer.createRenderRequest()
                    .setWaitForPresent(true)
                    .syncAndDraw();
            if (sync != HardwareRenderer.SYNC_OK
                    && sync != HardwareRenderer.SYNC_REDRAW_REQUESTED) {
                return null;
            }
            Image image = reader.acquireLatestImage();
            if (image == null) return null;
            try {
                HardwareBuffer buffer = image.getHardwareBuffer();
                if (buffer == null) return null;
                Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer,
                        ColorSpace.get(ColorSpace.Named.SRGB));
                if (hardware == null || hardware.isRecycled()) return null;
                // Durable HARDWARE copy (wrap is tied to the Image lifetime).
                Bitmap durable = hardware.copy(Bitmap.Config.HARDWARE, false);
                if (durable == null || durable.isRecycled()) {
                    durable = hardware.copy(Bitmap.Config.ARGB_8888, false);
                }
                if (hardware != durable && !hardware.isRecycled()) {
                    try { hardware.recycle(); } catch (Throwable ignored) { }
                }
                return durable;
            } finally {
                image.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "GPU RenderEffect blur failed, will CPU-fallback", t);
            return null;
        } finally {
            if (renderer != null) {
                try { renderer.destroy(); } catch (Throwable ignored) { }
            }
            if (reader != null) {
                try { reader.close(); } catch (Throwable ignored) { }
            }
        }
    }
}
