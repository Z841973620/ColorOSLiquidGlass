package net.z841973620.colorosliquidglass.glass;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.HardwareRenderer;
import android.graphics.PixelFormat;
import android.graphics.RenderNode;
import android.graphics.RecordingCanvas;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.view.View;

import java.util.function.Consumer;

/**
 * Renders HW-only content (e.g. AGSL {@link GlassDrawable}) into a software bitmap via
 * {@link HardwareRenderer} + {@link ImageReader}, so it can be pasted into the drag-folder
 * backdrop sample and refracted by the upper LiquidGlass pass.
 */
final class GlassHwRasterizer {
    private GlassHwRasterizer() {}

    static Bitmap render(int width, int height, Consumer<Canvas> drawer) {
        if (width <= 0 || height <= 0 || drawer == null) return null;
        ImageReader reader = null;
        HardwareRenderer renderer = null;
        try {
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 1,
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
                            | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
                            | HardwareBuffer.USAGE_CPU_READ_OFTEN);
            renderer = new HardwareRenderer();
            RenderNode node = new RenderNode("ColorOSLiquidGlassSnap");
            node.setPosition(0, 0, width, height);
            RecordingCanvas recording = node.beginRecording(width, height);
            try {
                drawer.accept(recording);
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
                Bitmap software = hardware.copy(Bitmap.Config.ARGB_8888, false);
                if (hardware != software) hardware.recycle();
                return software == null || software.isRecycled() ? null : software;
            } finally {
                image.close();
            }
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (renderer != null) {
                try {
                    renderer.destroy();
                } catch (Throwable ignored) { }
            }
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignored) { }
            }
        }
    }

    /** Convenience when the host view size matches the drawable bounds. */
    static Bitmap renderViewSized(View host, Consumer<Canvas> drawer) {
        if (host == null) return null;
        return render(host.getWidth(), host.getHeight(), drawer);
    }
}
