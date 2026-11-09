package nl.sebastiaan.baan;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;

/** Draws an app as a pointy-top hexagon in HUD (monochrome) or colour style. */
final class Hex {
    private static final Path path = new Path();
    private static final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint bmp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final RectF r = new RectF();
    private static int filterColor;
    private static PorterDuffColorFilter filter;

    private static PorterDuffColorFilter tint(int c) {
        if (filter == null || filterColor != c) { filter = new PorterDuffColorFilter(c, PorterDuff.Mode.SRC_IN); filterColor = c; }
        return filter;
    }

    static {
        line.setStyle(Paint.Style.STROKE);
    }

    static Path shape(float cx, float cy, float h) {
        path.reset();
        float rad = h / 2f;
        for (int i = 0; i < 6; i++) {
            double a = Math.toRadians(-90 + 60 * i);
            float x = cx + (float) (Math.cos(a) * rad), y = cy + (float) (Math.sin(a) * rad);
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        path.close();
        return path;
    }

    /**
     * @param glow 0..1 extra glow for the selected item
     */
    static void draw(Canvas cv, Apps.Item it, float cx, float cy, float h, boolean color,
                     int accent, int deep, float glow, int alpha) {
        Path p = shape(cx, cy, h);
        // glow halo: a few widening strokes, cheaper and safer than a blur filter
        if (glow > 0.01f) {
            for (int i = 3; i >= 1; i--) {
                line.setColor(Theme.alpha(accent, (int) (alpha * glow * 0.18f)));
                line.setStrokeWidth(h * 0.06f * i);
                cv.drawPath(p, line);
            }
        }
        if (color) {
            fill.setColor(Theme.alpha(0xFF0B1418, alpha));
            cv.drawPath(p, fill);
            if (it != null && it.color != null) {
                cv.save();
                cv.clipPath(p);
                float s = h * 0.80f;
                r.set(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2);
                bmp.setColorFilter(null);
                bmp.setAlpha(alpha);
                cv.drawBitmap(it.color, null, r, bmp);
                cv.restore();
            }
        } else {
            fill.setColor(Theme.alpha(deep, (int) (alpha * (0.35f + 0.35f * glow))));
            cv.drawPath(p, fill);
            if (it != null && it.mask != null) {
                float s = h * 0.56f;
                r.set(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2);
                bmp.setColorFilter(tint(glow > 0.5f ? Theme.TEXT : accent));
                bmp.setAlpha(alpha);
                cv.drawBitmap(it.mask, null, r, bmp);
            }
        }
        line.setColor(Theme.alpha(accent, (int) (alpha * (0.55f + 0.45f * glow))));
        line.setStrokeWidth(Math.max(1.5f, h * (0.018f + 0.012f * glow)));
        cv.drawPath(p, line);
    }

    static void badge(Canvas cv, float x, float y, float rad, int n, int accent, Paint text) {
        if (n <= 0) return;
        fill.setColor(Theme.BG);
        cv.drawCircle(x, y, rad * 1.22f, fill);
        fill.setColor(accent);
        cv.drawCircle(x, y, rad, fill);
        text.setColor(Theme.BG);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(rad * 1.15f);
        cv.drawText(n > 9 ? "9+" : String.valueOf(n), x, y + rad * 0.4f, text);
    }

    static Bitmap unused() { return null; }
}
