package net.z841973620.colorosliquidglass.glass;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.Log;
import android.view.View;

import net.z841973620.colorosliquidglass.GlassConfig;

/** Liquid-glass drawable backed by a shared recording of the pixels behind its owning UI item. */
public final class GlassDrawable extends Drawable {
    private static final String TAG = "ColorOSLiquidGlass";
    private static final String SHADER = """
            uniform shader image;
            uniform float2 size;
            uniform float2 sampleOrigin;
            uniform float2 sampleScale;
            uniform float4 cornerRadii;
            uniform float blurRadius;
            uniform float refractionHeight;
            uniform float refractionAmount;
            uniform float depthEffect;
            uniform float chromaticAberration;
            uniform float glassIntensity;
            uniform float surfaceOpacity;
            uniform float reflectionIntensity;

            float radiusAt(float2 coord, float4 radii) {
                if (coord.x >= 0.0) {
                    if (coord.y <= 0.0) return radii.y;
                    return radii.z;
                }
                if (coord.y <= 0.0) return radii.x;
                return radii.w;
            }

            float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
                float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
                float outside = length(max(cornerCoord, 0.0)) - radius;
                float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
                return outside + inside;
            }

            float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
                float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
                if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
                    return sign(coord) * normalize(max(cornerCoord, 0.0001));
                }
                float gradX = step(cornerCoord.y, cornerCoord.x);
                return sign(coord) * float2(gradX, 1.0 - gradX);
            }

            float circleMap(float x) {
                x = clamp(x, 0.0, 1.0);
                return 1.0 - sqrt(max(0.0, 1.0 - x * x));
            }

            half4 sampleBackdrop(float2 coord) {
                // RuntimeShader does not reliably honor BitmapShader local matrices, so map
                // view-space coords onto a possibly-downscaled capture bitmap here.
                return image.eval((coord + sampleOrigin) * sampleScale);
            }

            half4 vibrant(float2 coord) {
                float r = blurRadius;
                half4 c;
                if (r <= 0.1) {
                    c = sampleBackdrop(coord);
                } else {
                    c = sampleBackdrop(coord) * 0.28;
                    c += sampleBackdrop(coord + float2( r, 0.0)) * 0.18;
                    c += sampleBackdrop(coord + float2(-r, 0.0)) * 0.18;
                    c += sampleBackdrop(coord + float2(0.0,  r)) * 0.18;
                    c += sampleBackdrop(coord + float2(0.0, -r)) * 0.18;
                }
                half luminance = dot(c.rgb, half3(0.2126, 0.7152, 0.0722));
                c.rgb = mix(half3(luminance), c.rgb, 1.18);
                c.rgb = (c.rgb - 0.5) * 1.04 + 0.5;
                return c;
            }

            half4 dispersed(float2 coord, float2 delta) {
                half4 center = vibrant(coord);
                half4 positive = vibrant(coord + delta);
                half4 negative = vibrant(coord - delta);
                return half4(positive.r, center.g, negative.b, center.a);
            }

            half4 main(float2 coord) {
                float2 halfSize = size * 0.5;
                float2 centered = coord - halfSize;
                float radius = radiusAt(centered, cornerRadii);
                float sd = sdRoundedRect(centered, halfSize, radius);
                if (sd > 0.0) return half4(0.0);

                float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
                float2 normal = normalize(gradSdRoundedRect(centered, halfSize, gradRadius)
                    + depthEffect * glassIntensity * normalize(centered + 0.0001));
                half4 color;
                if (refractionHeight <= 0.0 || refractionAmount <= 0.0 || -sd >= refractionHeight) {
                    color = vibrant(coord);
                } else {
                    float d = circleMap(1.0 - (-sd / refractionHeight)) * refractionAmount;
                    float2 refractedCoord = coord - d * normal;
                    if (chromaticAberration <= 0.0) {
                        color = vibrant(refractedCoord);
                    } else {
                        float intensity = ((centered.x * centered.y) / max(1.0, halfSize.x * halfSize.y));
                        color = dispersed(refractedCoord, d * normal * intensity * chromaticAberration);
                    }
                }

                float rimWidth = max(1.5, min(max(refractionHeight, 6.0), 18.0));
                float rim = 1.0 - smoothstep(-rimWidth, 0.0, sd);
                float2 lightDirection = normalize(float2(-0.72, -0.70));
                float reflection = pow(max(dot(-normal, lightDirection), 0.0), 3.0);
                float shade = pow(max(dot(normal, lightDirection), 0.0), 2.0);
                color.rgb *= half(1.0 - shade * rim * 0.10 * glassIntensity);
                color.rgb += half3(rim * (0.025 + reflection * 0.14 * reflectionIntensity) * glassIntensity);
                color.a *= half(surfaceOpacity);
                return color;
            }
            """;

    private final View owner;
    private final GlassConfig config;
    private final Paint glassPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fallbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path();
    private final float[] radii = new float[4];
    private final RuntimeShader runtimeShader;
    private final BackdropCapture backdrop;
    private Bitmap cachedSnapshot;
    private BitmapShader cachedImageShader;
    private boolean shaderFailureLogged;
    private int alpha = 255;

    public GlassDrawable(View owner, GlassConfig config) {
        this.owner = owner;
        this.config = config;
        this.backdrop = BackdropCapture.register(owner);
        RuntimeShader shader = null;
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                shader = new RuntimeShader(SHADER);
            } catch (Throwable error) {
                shaderFailureLogged = true;
                Log.e(TAG, "AGSL compilation failed for " + owner.getClass().getName(), error);
            }
        }
        runtimeShader = shader;
        fallbackPaint.setColor(Color.argb(54, 238, 245, 255));
        highlightPaint.setStyle(Paint.Style.STROKE);
        highlightPaint.setColor(Color.argb(Math.round(255f * 0.38f), 255, 255, 255));
        if (Build.VERSION.SDK_INT >= 29) highlightPaint.setBlendMode(BlendMode.PLUS);
    }

    public void setCornerRadii(float[] cornerRadii) {
        if (cornerRadii == null || cornerRadii.length < 4) return;
        setCornerRadii(cornerRadii[0], cornerRadii[1], cornerRadii[2], cornerRadii[3]);
    }

    public void setCornerRadii(float topLeft, float topRight, float bottomRight, float bottomLeft) {
        // Installation commonly happens before Drawable bounds exist. Preserve requested radii and
        // only clamp a draw-time copy, otherwise every folder is permanently reduced to square.
        float tl = Math.max(0f, topLeft);
        float tr = Math.max(0f, topRight);
        float br = Math.max(0f, bottomRight);
        float bl = Math.max(0f, bottomLeft);
        if (Float.compare(radii[0], tl) == 0 && Float.compare(radii[1], tr) == 0
                && Float.compare(radii[2], br) == 0 && Float.compare(radii[3], bl) == 0) return;
        radii[0] = tl;
        radii[1] = tr;
        radii[2] = br;
        radii[3] = bl;
        rebuildShape(getBounds());
        invalidateSelf();
    }

    public float[] getCornerRadii() { return radii.clone(); }

    @Override protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        rebuildShape(bounds);
    }

    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) return;
        int save = canvas.save();
        canvas.clipPath(shape);
        boolean drewBackdrop = false;
        Bitmap snapshot = backdrop == null ? null : backdrop.bitmap();
        if (canvas.isHardwareAccelerated() && runtimeShader != null
                && snapshot != null && !snapshot.isRecycled()) {
            try {
                if (snapshot != cachedSnapshot || cachedImageShader == null) {
                    cachedSnapshot = snapshot;
                    cachedImageShader = new BitmapShader(
                            snapshot, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                }
                // Identity matrix: scaling is applied in AGSL via sampleScale.
                cachedImageShader.setLocalMatrix(null);
                runtimeShader.setInputShader("image", cachedImageShader);
                float min = Math.max(1f, Math.min(bounds.width(), bounds.height()));
                float[] corner = clampedRadii(bounds);
                float[] sampleScale = backdrop.sampleScale();
                runtimeShader.setFloatUniform("size", bounds.width(), bounds.height());
                runtimeShader.setFloatUniform("sampleOrigin", 0f, 0f);
                runtimeShader.setFloatUniform("sampleScale", sampleScale[0], sampleScale[1]);
                // float4 must use the 4-float overload; float[] is for float array uniforms.
                runtimeShader.setFloatUniform("cornerRadii",
                        corner[0], corner[1], corner[2], corner[3]);
                runtimeShader.setFloatUniform("blurRadius", effectiveShaderBlurRadius());
                float intensity = clamp01(config.glassIntensity);
                runtimeShader.setFloatUniform("refractionHeight",
                        Math.max(0f, config.refractionHeight) * intensity * min * 0.5f);
                runtimeShader.setFloatUniform("refractionAmount",
                        Math.max(0f, config.refractionAmount) * intensity * min);
                runtimeShader.setFloatUniform("depthEffect", intensity);
                runtimeShader.setFloatUniform("chromaticAberration",
                        Math.max(0f, Math.min(1f, config.chromaticAberration)));
                runtimeShader.setFloatUniform("glassIntensity", intensity);
                runtimeShader.setFloatUniform("surfaceOpacity", 1f - clamp01(config.transparency) * 0.7f);
                runtimeShader.setFloatUniform("reflectionIntensity", clamp01(config.reflectionIntensity));
                glassPaint.setShader(runtimeShader);
                glassPaint.setAlpha(alpha);
                canvas.translate(bounds.left, bounds.top);
                canvas.drawRect(0f, 0f, bounds.width(), bounds.height(), glassPaint);
                canvas.translate(-bounds.left, -bounds.top);
                drewBackdrop = true;
            } catch (Throwable error) {
                glassPaint.setShader(null);
                if (!shaderFailureLogged) {
                    shaderFailureLogged = true;
                    Log.e(TAG, "AGSL draw failed for " + owner.getClass().getName(), error);
                }
            }
        }
        if (!drewBackdrop) {
            fallbackPaint.setAlpha(Math.round(alpha * 0.42f));
            canvas.drawPath(shape, fallbackPaint);
        }
        canvas.restoreToCount(save);
        drawHighlight(canvas);
    }

    private void drawHighlight(Canvas canvas) {
        float density = owner.getResources().getDisplayMetrics().density;
        float width = Math.max(1f, 0.5f * density);
        highlightPaint.setStrokeWidth(width);
        highlightPaint.setAlpha(Math.round(alpha * 0.16f
                * clamp01(config.highlightIntensity) * clamp01(config.glassIntensity)));
        canvas.drawPath(shape, highlightPaint);
    }

    private float[] clampedRadii(Rect bounds) {
        float limit = Math.max(0f, Math.min(bounds.width(), bounds.height()) * 0.5f);
        return new float[] {
                Math.min(radii[0], limit), Math.min(radii[1], limit),
                Math.min(radii[2], limit), Math.min(radii[3], limit)
        };
    }

    private void rebuildShape(Rect bounds) {
        float[] r = clampedRadii(bounds);
        float[] rr = new float[] { r[0], r[0], r[1], r[1], r[2], r[2], r[3], r[3] };
        shape.reset();
        shape.addRoundRect(new RectF(bounds), rr, Path.Direction.CW);
    }

    private float effectiveShaderBlurRadius() {
        return Math.max(0f, config.blurRadius) * owner.getResources().getDisplayMetrics().density;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    @Override public void setAlpha(int alpha) {
        int next = Math.max(0, Math.min(255, alpha));
        if (this.alpha == next) return;
        this.alpha = next;
        invalidateSelf();
    }
    @Override public int getAlpha() { return alpha; }
    @Override public void setColorFilter(ColorFilter colorFilter) { glassPaint.setColorFilter(colorFilter); invalidateSelf(); }
    @SuppressWarnings("deprecation") @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
