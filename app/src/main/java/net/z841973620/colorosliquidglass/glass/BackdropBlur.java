package net.z841973620.colorosliquidglass.glass;

import android.graphics.Bitmap;

/**
 * CPU stack-blur for desktop glass backdrops.
 * <p>
 * Console preview uses Compose {@code RenderEffect} blur; Launcher glass feeds a bitmap into
 * AGSL, so blur must happen on that bitmap. The old AGSL 5-tap ±radius cross only echoed
 * the image and looked like ghosting.
 */
public final class BackdropBlur {
    private BackdropBlur() {}

    /**
     * @param radiusPx blur radius in pixels; {@code <= 0} returns {@code src} unchanged
     * @return blurred ARGB_8888 bitmap (may be {@code src} mutated in-place when mutable)
     */
    public static Bitmap blur(Bitmap src, float radiusPx) {
        if (src == null || src.isRecycled()) return src;
        int radius = Math.round(radiusPx);
        if (radius < 1) return src;
        radius = Math.min(radius, 64);
        Bitmap bitmap;
        if (src.isMutable() && src.getConfig() == Bitmap.Config.ARGB_8888) {
            bitmap = src;
        } else {
            bitmap = src.copy(Bitmap.Config.ARGB_8888, true);
            if (bitmap == null) return src;
        }
        stackBlur(bitmap, radius);
        return bitmap;
    }

    /** Mario Klingemann stack blur (in-place). */
    private static void stackBlur(Bitmap bitmap, int radius) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        if (w <= 0 || h <= 0) return;
        int[] pix = new int[w * h];
        bitmap.getPixels(pix, 0, w, 0, 0, w, h);

        int wm = w - 1;
        int hm = h - 1;
        int wh = w * h;
        int div = radius + radius + 1;

        int[] r = new int[wh];
        int[] g = new int[wh];
        int[] b = new int[wh];
        int[] vmin = new int[Math.max(w, h)];

        int divsum = (div + 1) >> 1;
        divsum *= divsum;
        int[] dv = new int[256 * divsum];
        for (int i = 0; i < dv.length; i++) dv[i] = i / divsum;

        int yi = 0;
        int yw = 0;
        int[][] stack = new int[div][3];

        for (int y = 0; y < h; y++) {
            int rsum = 0, gsum = 0, bsum = 0;
            int rinsum = 0, ginsum = 0, binsum = 0;
            int routsum = 0, goutsum = 0, boutsum = 0;
            for (int i = -radius; i <= radius; i++) {
                int p = pix[yi + Math.min(wm, Math.max(i, 0))];
                int[] sir = stack[i + radius];
                sir[0] = (p >> 16) & 0xff;
                sir[1] = (p >> 8) & 0xff;
                sir[2] = p & 0xff;
                int rbs = radius + 1 - Math.abs(i);
                rsum += sir[0] * rbs;
                gsum += sir[1] * rbs;
                bsum += sir[2] * rbs;
                if (i > 0) {
                    rinsum += sir[0];
                    ginsum += sir[1];
                    binsum += sir[2];
                } else {
                    routsum += sir[0];
                    goutsum += sir[1];
                    boutsum += sir[2];
                }
            }
            int stackpointer = radius;
            for (int x = 0; x < w; x++) {
                r[yi] = dv[rsum];
                g[yi] = dv[gsum];
                b[yi] = dv[bsum];

                rsum -= routsum;
                gsum -= goutsum;
                bsum -= boutsum;

                int stackstart = stackpointer - radius + div;
                int[] sir = stack[stackstart % div];

                routsum -= sir[0];
                goutsum -= sir[1];
                boutsum -= sir[2];

                if (y == 0) vmin[x] = Math.min(x + radius + 1, wm);
                int p = pix[yw + vmin[x]];

                sir[0] = (p >> 16) & 0xff;
                sir[1] = (p >> 8) & 0xff;
                sir[2] = p & 0xff;

                rinsum += sir[0];
                ginsum += sir[1];
                binsum += sir[2];

                rsum += rinsum;
                gsum += ginsum;
                bsum += binsum;

                stackpointer = (stackpointer + 1) % div;
                sir = stack[stackpointer % div];

                routsum += sir[0];
                goutsum += sir[1];
                boutsum += sir[2];

                rinsum -= sir[0];
                ginsum -= sir[1];
                binsum -= sir[2];

                yi++;
            }
            yw += w;
        }

        for (int x = 0; x < w; x++) {
            int rsum = 0, gsum = 0, bsum = 0;
            int rinsum = 0, ginsum = 0, binsum = 0;
            int routsum = 0, goutsum = 0, boutsum = 0;
            int yp = -radius * w;
            for (int i = -radius; i <= radius; i++) {
                yi = Math.max(0, yp) + x;
                int[] sir = stack[i + radius];
                sir[0] = r[yi];
                sir[1] = g[yi];
                sir[2] = b[yi];
                int rbs = radius + 1 - Math.abs(i);
                rsum += r[yi] * rbs;
                gsum += g[yi] * rbs;
                bsum += b[yi] * rbs;
                if (i > 0) {
                    rinsum += sir[0];
                    ginsum += sir[1];
                    binsum += sir[2];
                } else {
                    routsum += sir[0];
                    goutsum += sir[1];
                    boutsum += sir[2];
                }
                if (i < hm) yp += w;
            }
            yi = x;
            int stackpointer = radius;
            for (int y = 0; y < h; y++) {
                pix[yi] = (pix[yi] & 0xff000000)
                        | (dv[rsum] << 16)
                        | (dv[gsum] << 8)
                        | dv[bsum];

                rsum -= routsum;
                gsum -= goutsum;
                bsum -= boutsum;

                int stackstart = stackpointer - radius + div;
                int[] sir = stack[stackstart % div];

                routsum -= sir[0];
                goutsum -= sir[1];
                boutsum -= sir[2];

                if (x == 0) vmin[y] = Math.min(y + radius + 1, hm) * w;
                int p = x + vmin[y];

                sir[0] = r[p];
                sir[1] = g[p];
                sir[2] = b[p];

                rinsum += sir[0];
                ginsum += sir[1];
                binsum += sir[2];

                rsum += rinsum;
                gsum += ginsum;
                bsum += binsum;

                stackpointer = (stackpointer + 1) % div;
                sir = stack[stackpointer];

                routsum += sir[0];
                goutsum += sir[1];
                boutsum += sir[2];

                rinsum -= sir[0];
                ginsum -= sir[1];
                binsum -= sir[2];

                yi += w;
            }
        }

        bitmap.setPixels(pix, 0, w, 0, 0, w, h);
    }
}
