package nl.sebastiaan.baan;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** The home screen: clock ring, data blocks, thumb arc and command bar, all on one canvas. */
public class HudView extends View implements Apps.Listener {

    // ---- regions / accessibility ids
    static final int R_NONE = 0, R_RING = 1, R_AGENDA = 2, R_INBOX = 3, R_CMD = 4, R_GEAR = 5, R_BAR = 6, R_INT = 7, R_ARC = 100;
    static final int R_BARID = 1000;   // accessibility id base for pin-bar icons

    final MainActivity act;
    final SharedPreferences prefs;
    final Handler h = new Handler(Looper.getMainLooper());
    static final Locale NL = new Locale("nl");

    // ---- geometry
    int W, H, insetTop, insetBottom;
    float u;                       // 1 "dp" relative to a 412 wide screen
    float ts;                      // text scale
    float rcx, rcy, R;             // ring
    final RectF boxA = new RectF(), boxB = new RectF(), cmd = new RectF(), gear = new RectF(), intChip = new RectF();
    float arcLabelY, acx, acy, AR, arcTop, iconH;
    // pin-bar (eigen rij vastgezette apps boven de baan)
    final RectF barRect = new RectF();
    final java.util.List<Apps.Item> barItems = new ArrayList<>();
    float barIcon, barPitch, barStartX, barCenterY, barScroll, barMaxScroll;
    boolean barDragging;
    static final float STEP = (float) Math.toRadians(24), MAXA = (float) Math.toRadians(62);

    // ---- state
    int battery = -1;
    boolean charging;
    String evTitle;                // next calendar event
    long evBegin, evEnd;
    boolean evAllDay, calAccess;
    long bootStart;
    boolean visible;
    int tour = -1;
    Peilingen peil;

    // arc physics
    float scroll, vel;
    boolean flinging, snapping, dragging;
    int lastCenter = -1;

    // touch
    float downX, downY, lastX;
    long downT;
    int downRegion;
    boolean moved, longFired, swiped;
    VelocityTracker vt;
    final int slop;

    // ---- paints & cached shaders
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    final Paint bp = new Paint(Paint.FILTER_BITMAP_FLAG);
    Bitmap bg;
    Paint scanPaint;
    final Path arcGuide = new Path();
    final RectF tmp = new RectF();
    final Matrix m = new Matrix();
    SweepGradient sweepShader, batteryShader;
    LinearGradient cmdShader;
    int cmdShaderColor;
    final DashPathEffect dots = new DashPathEffect(new float[]{2, 7}, 0);
    final DashPathEffect dashes = new DashPathEffect(new float[]{4, 10}, 0);
    final SimpleDateFormat fDate = new SimpleDateFormat("EEE dd.MM", NL);
    final SimpleDateFormat fTime = new SimpleDateFormat("HH:mm", NL);
    final Date now = new Date();

    public HudView(Context c) {
        super(c);
        act = (MainActivity) c;
        prefs = App.prefs(c);
        Theme.load(c);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        setHapticFeedbackEnabled(true);
        setFocusable(true);
        bootStart = SystemClock.uptimeMillis();
        if (!prefs.getBoolean("tourDone", false)) tour = 0;
        peil = new Peilingen(c, new Runnable() { @Override public void run() { invalidate(); } });
    }

    // ===================================================================== lifecycle
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); Apps.listeners.add(this); }
    @Override protected void onDetachedFromWindow() { Apps.listeners.remove(this); super.onDetachedFromWindow(); }

    @Override public void onApps() {
        int n = Apps.arc.size();
        if (!wrap() && scroll > n - 1) scroll = Math.max(0, n - 1);
        refreshBar();
        invalidate();
    }

    void refreshBar() {
        barItems.clear();
        barItems.addAll(Apps.bar(getContext()));
    }

    /** Positions of the pin-bar icons; recomputed each use because barScroll changes. */
    void computeBar() {
        float pad = 12 * u;
        barCenterY = barRect.centerY();
        barIcon = Math.min(barRect.height() * 0.86f, 48 * u);
        barPitch = barIcon * 1.18f;
        int n = barItems.size();
        float innerW = barRect.width() - 2 * pad;
        float totalW = n * barPitch;
        barMaxScroll = Math.max(0, totalW - innerW);
        if (barScroll < 0) barScroll = 0;
        if (barScroll > barMaxScroll) barScroll = barMaxScroll;
        if (totalW <= innerW) barStartX = barRect.left + (barRect.width() - totalW) / 2f + barPitch / 2f;
        else barStartX = barRect.left + pad + barPitch / 2f - barScroll;
    }

    float barCenterX(int i) { return barStartX + i * barPitch; }

    /** Index of the pin-bar icon under (x,y), or -1. */
    int hitBar(float x, float y) {
        if (!barRect.contains(x, y)) return -1;
        computeBar();
        int n = barItems.size(), best = -1;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            float cx = barCenterX(i);
            if (cx < barRect.left - barPitch || cx > barRect.right + barPitch) continue;
            float d = Math.abs(x - cx);
            if (d < barPitch * 0.55f && d < bestD) { bestD = d; best = i; }
        }
        return best;
    }

    void launchBar(int i) {
        if (i < 0 || i >= barItems.size()) return;
        if (!Apps.launch(getContext(), barItems.get(i))) act.toast("Kan app niet openen");
    }

    void onShow() {
        visible = true;
        bootStart = SystemClock.uptimeMillis();
        loadCalendar();
        if (peil != null) peil.start();
        kick();
        h.removeCallbacks(tick);
        h.post(tick);
    }

    void onHide() { visible = false; h.removeCallbacks(tick); if (peil != null) peil.stop(); }

    final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            invalidate();
            if (System.currentTimeMillis() / 1000 % 60 == 0) loadCalendar();
            h.postDelayed(this, 1000 - System.currentTimeMillis() % 1000);
        }
    };

    boolean effects() { return prefs.getInt("fx", 0) == 0; }

    // frame loop for animations (only runs while something moves)
    boolean frameQueued;
    long lastFrame;
    final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        @Override public void doFrame(long t) {
            frameQueued = false;
            float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (t - lastFrame) / 1e9f);
            lastFrame = t;
            stepPhysics(dt);
            invalidate();
            if (animating()) queue(); else lastFrame = 0;
        }
    };

    boolean animating() {
        return visible && (flinging || snapping || dragging || bootProgress() < 1 || (effects() && tour < 0));
    }

    void queue() {
        if (!frameQueued) { frameQueued = true; Choreographer.getInstance().postFrameCallback(frame); }
    }

    void kick() { lastFrame = 0; queue(); }

    float bootProgress() {
        float t = (SystemClock.uptimeMillis() - bootStart) / 900f;
        return Math.min(1f, t);
    }

    static float ease(float t) { t = Math.max(0, Math.min(1, t)); return 1 - (1 - t) * (1 - t) * (1 - t); }

    void onBattery(int level, boolean ch) { battery = level; charging = ch; invalidate(); }

    void setInsets(int top, int bottom) { insetTop = top; insetBottom = bottom; layout(); invalidate(); }

    // ===================================================================== calendar
    void loadCalendar() {
        final Context c = getContext();
        calAccess = c.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (!calAccess) return;
        new Thread(new Runnable() {
            @Override public void run() {
                String title = null;
                long b = 0, e = 0;
                boolean all = false;
                android.database.Cursor cur = null;
                try {
                    long n = System.currentTimeMillis();
                    android.net.Uri.Builder ub = android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon();
                    android.content.ContentUris.appendId(ub, n - 6 * 3600000L);
                    android.content.ContentUris.appendId(ub, n + 36 * 3600000L);
                    cur = c.getContentResolver().query(ub.build(), new String[]{
                                    android.provider.CalendarContract.Instances.TITLE,
                                    android.provider.CalendarContract.Instances.BEGIN,
                                    android.provider.CalendarContract.Instances.END,
                                    android.provider.CalendarContract.Instances.ALL_DAY},
                            null, null, android.provider.CalendarContract.Instances.BEGIN + " ASC");
                    while (cur != null && cur.moveToNext()) {
                        long bb = cur.getLong(1), ee = cur.getLong(2);
                        boolean ad = cur.getInt(3) == 1;
                        if (ee <= n) continue;
                        if (ad && title != null) continue;
                        title = cur.getString(0); b = bb; e = ee; all = ad;
                        if (!ad) break;
                    }
                } catch (Throwable ignored) {
                } finally { if (cur != null) cur.close(); }
                final String ft = title; final long fb = b, fe = e; final boolean fa = all;
                h.post(new Runnable() { @Override public void run() {
                    evTitle = ft; evBegin = fb; evEnd = fe; evAllDay = fa; invalidate();
                } });
            }
        }).start();
    }

    // ===================================================================== layout
    @Override protected void onSizeChanged(int w, int hh, int ow, int oh) { W = w; H = hh; layout(); }

    void layout() {
        if (W == 0 || H == 0) return;
        u = W / 412f;
        ts = Theme.size(getContext());
        float top = insetTop + 18 * u;
        R = 118 * u;
        rcx = W / 2f;
        rcy = top + 18 * u + R * 1.14f;
        float bt = rcy + R * 1.14f + 30 * u;
        float bh = 78 * u * Math.min(ts, 1.2f);
        float m = 18 * u, gap = 10 * u;
        boxA.set(m, bt, W / 2f - gap / 2, bt + bh);
        boxB.set(W / 2f + gap / 2, bt, W - m, bt + bh);
        float ch = 50 * u;
        cmd.set(m, H - insetBottom - 18 * u - ch, W - m, H - insetBottom - 18 * u);
        gear.set(W - m - 40 * u, insetTop + 6 * u, W - m + 4 * u, insetTop + 46 * u);
        intChip.set(m - 4 * u, insetTop + 8 * u, m + 68 * u, insetTop + 42 * u);
        // pin-bar: een strook tussen de kaders en de baan
        refreshBar();
        float barTop = boxA.bottom + 12 * u;
        float barH = 52 * u * Math.min(ts, 1.2f);
        barRect.set(m, barTop, W - m, barTop + barH);
        arcLabelY = barRect.bottom + 28 * u;
        iconH = 62 * u;
        AR = 236 * u;
        // the arc: its top sits a little under the label, the sides drop toward the command bar
        float space = cmd.top - arcLabelY;
        arcTop = arcLabelY + Math.max(iconH * 0.9f, space * 0.30f);
        acx = W / 2f;
        acy = arcTop + AR;
        // keep the outer icons above the command bar
        float drop = (float) (AR * (1 - Math.cos(MAXA)));
        if (arcTop + drop + iconH * 0.9f > cmd.top - 8 * u) {
            arcTop = cmd.top - 8 * u - iconH * 0.9f - drop;
            acy = arcTop + AR;
        }
        arcGuide.reset();
        tmp.set(acx - AR, acy - AR, acx + AR, acy + AR);
        arcGuide.addArc(tmp, -90 - 70, 140);
        buildBackground();
        cmdShader = null;
        int acc = Theme.accent(getContext()), sec = Theme.second(getContext());
        sweepShader = new SweepGradient(0, 0, new int[]{0x00000000, 0x00000000, Theme.alpha(acc, 70)},
                new float[]{0f, 0.82f, 1f});
        batteryShader = new SweepGradient(rcx, rcy, new int[]{acc, sec, acc}, null);
        this.m.reset();
        this.m.setRotate(-90, rcx, rcy);
        batteryShader.setLocalMatrix(this.m);
    }

    void buildBackground() {
        Context c = getContext();
        bg = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bg);
        Paint g = new Paint(Paint.ANTI_ALIAS_FLAG);
        cv.drawColor(Theme.BG);
        g.setShader(new RadialGradient(W / 2f, rcy, H * 0.75f, Theme.bgTop(c), Theme.BG, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, W, H, g);
        g.setShader(null);
        g.setColor(Theme.alpha(Theme.accent(c), 12));
        g.setStrokeWidth(1);
        float step = 24 * u;
        for (float x = (W / 2f) % step; x < W; x += step) cv.drawLine(x, 0, x, H, g);
        for (float y = rcy % step; y < H; y += step) cv.drawLine(0, y, W, y, g);
        // vignette
        g.setShader(new RadialGradient(W / 2f, H / 2f, H * 0.7f, 0x00000000, 0x99000000, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, W, H, g);
        // scanlines as a tiny tile shader drawn on top of everything
        int sh = Math.max(3, Math.round(3 * u));
        Bitmap tile = Bitmap.createBitmap(1, sh, Bitmap.Config.ARGB_8888);
        tile.setPixel(0, sh - 1, 0x2E000000);
        scanPaint = new Paint();
        scanPaint.setShader(new BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
    }

    // ===================================================================== arc helpers
    boolean wrap() { return Apps.arc.size() >= 7; }

    float rel(int i) {
        int n = Apps.arc.size();
        float r = i - scroll;
        if (wrap()) { r = ((r % n) + n) % n; if (r > n / 2f) r -= n; }
        return r;
    }

    int centerIndex() {
        int n = Apps.arc.size();
        if (n == 0) return -1;
        int c = Math.round(scroll);
        return wrap() ? ((c % n) + n) % n : Math.max(0, Math.min(n - 1, c));
    }

    void stepPhysics(float dt) {
        int n = Apps.arc.size();
        if (flinging) {
            scroll += vel * dt;
            vel *= (float) Math.exp(-dt * 3.4);
            if (!wrap() && (scroll < 0 || scroll > n - 1)) vel *= 0.5f;
            if (Math.abs(vel) < 0.8f) { flinging = false; snapping = true; }
        }
        if (snapping) {
            float target = Math.round(scroll);
            if (!wrap()) target = Math.max(0, Math.min(Math.max(0, n - 1), target));
            float d = target - scroll;
            scroll += d * Math.min(1f, dt * 14f);
            if (Math.abs(d) < 0.002f) { scroll = target; snapping = false; }
        }
        int c = centerIndex();
        if (c != lastCenter && (dragging || flinging)) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        lastCenter = c;
    }

    // ===================================================================== drawing
    @Override
    protected void onDraw(Canvas cv) {
        if (W == 0) return;
        Context c = getContext();
        int acc = Theme.accent(c), sec = Theme.second(c), deep = Theme.deep(c);
        long t = SystemClock.uptimeMillis();
        float boot = ease(bootProgress());
        now.setTime(System.currentTimeMillis());
        cv.drawBitmap(bg, 0, 0, bp);
        drawRing(cv, acc, sec, boot, t);
        drawBoxes(cv, acc, boot);
        drawBar(cv, acc, deep, boot);
        drawArc(cv, acc, deep, boot, t);
        drawCmd(cv, acc);
        drawGear(cv, acc);
        drawIntChip(cv, acc);
        cv.drawRect(0, 0, W, H, scanPaint);
        if (tour >= 0) drawTour(cv, acc);
        if (animating()) queue();
    }

    void text(Canvas cv, String s, float x, float y, float size, int color, boolean bold, Paint.Align al, float track) {
        tp.setTypeface(bold ? Theme.monoBold : Theme.mono);
        tp.setTextSize(size);
        tp.setColor(color);
        tp.setTextAlign(al);
        tp.setLetterSpacing(track);
        cv.drawText(s, x, y, tp);
        tp.setLetterSpacing(0);
    }

    String fit(String s, float size, boolean bold, float max, float track) {
        tp.setTypeface(bold ? Theme.monoBold : Theme.mono);
        tp.setTextSize(size);
        tp.setLetterSpacing(track);
        if (tp.measureText(s) <= max) { tp.setLetterSpacing(0); return s; }
        while (s.length() > 1 && tp.measureText(s + "…") > max) s = s.substring(0, s.length() - 1);
        tp.setLetterSpacing(0);
        return s + "…";
    }

    void drawRing(Canvas cv, int acc, int sec, float boot, long t) {
        float sw = 9 * u;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.BUTT);
        p.setShader(null);
        p.setPathEffect(null);
        // sweep (radar)
        if (effects()) {
            float ang = (t % 7000) / 7000f * 360f;
            cv.save();
            cv.translate(rcx, rcy);
            cv.rotate(ang - 90);
            p.setStyle(Paint.Style.FILL);
            p.setShader(sweepShader);
            cv.drawCircle(0, 0, R - sw, p);
            p.setShader(null);
            cv.restore();
            p.setStyle(Paint.Style.STROKE);
        }
        tmp.set(rcx - R, rcy - R, rcx + R, rcy + R);
        // battery track + arc with layered glow
        p.setStrokeWidth(sw);
        p.setColor(Theme.alpha(acc, 26));
        cv.drawOval(tmp, p);
        float lvl = battery < 0 ? 0 : battery / 100f;
        float sweepB = 360f * lvl * boot;
        int lowCol = battery >= 0 && battery <= 15 && !charging ? 0xFFFF4D5E : 0;
        if (lowCol == 0) p.setShader(batteryShader); else p.setColor(lowCol);
        for (int i = 3; i >= 0; i--) {
            p.setStrokeWidth(sw + i * 5 * u);
            p.setAlpha(i == 0 ? 255 : 26);
            cv.drawArc(tmp, -90, sweepB, false, p);
        }
        p.setShader(null);
        p.setAlpha(255);
        // inner dotted ring + seconds dot
        float ri = R * 0.86f;
        p.setStrokeWidth(1.4f * u);
        p.setPathEffect(dots);
        p.setColor(Theme.alpha(acc, 90));
        cv.drawCircle(rcx, rcy, ri, p);
        p.setPathEffect(null);
        Calendar cal = Calendar.getInstance();
        float secs = cal.get(Calendar.SECOND) + (effects() ? (System.currentTimeMillis() % 1000) / 1000f : 0);
        double sa = Math.toRadians(-90 + secs * 6);
        p.setStyle(Paint.Style.FILL);
        float sx = rcx + (float) Math.cos(sa) * ri, sy = rcy + (float) Math.sin(sa) * ri;
        p.setColor(Theme.alpha(acc, 60));
        cv.drawCircle(sx, sy, 6 * u, p);
        p.setColor(Theme.TEXT);
        cv.drawCircle(sx, sy, 2.6f * u, p);
        p.setStyle(Paint.Style.STROKE);
        // outer day ring
        float ro = R * 1.14f;
        float day = (cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)) / 1440f;
        tmp.set(rcx - ro, rcy - ro, rcx + ro, rcy + ro);
        p.setStrokeWidth(2 * u);
        p.setColor(Theme.alpha(sec, 36));
        cv.drawOval(tmp, p);
        p.setColor(sec);
        cv.drawArc(tmp, -90, 360f * day * boot, false, p);
        // ticks: 24 hour marks on the outer ring, four long ones
        for (int i = 0; i < 24; i++) {
            double a = Math.toRadians(-90 + i * 15);
            boolean big = i % 6 == 0;
            float r0 = ro + (big ? 6 : 4) * u, r1 = ro + (big ? 15 : 9) * u;
            p.setStrokeWidth((big ? 2.2f : 1.2f) * u);
            p.setColor(Theme.alpha(acc, big ? 220 : 90));
            cv.drawLine(rcx + (float) Math.cos(a) * r0, rcy + (float) Math.sin(a) * r0,
                    rcx + (float) Math.cos(a) * r1, rcy + (float) Math.sin(a) * r1, p);
        }
        drawPeilingen(cv, acc);
        // centre text
        String date = fDate.format(now).toUpperCase(NL).replace(".", "").replaceFirst(" ", " ");
        date = fDate.format(now).toUpperCase(NL);
        text(cv, date, rcx, rcy - 40 * u, 12 * u * ts, acc, false, Paint.Align.CENTER, 0.3f);
        String time = fTime.format(now);
        tp.setShadowLayer(16 * u, 0, 0, Theme.alpha(acc, 200));
        text(cv, time, rcx, rcy + 22 * u, 60 * u * Math.min(ts, 1.15f), Theme.TEXT, false, Paint.Align.CENTER, -0.02f);
        tp.clearShadowLayer();
        String sub = battery < 0 ? "" : (charging ? "LADEN " : "ENERGIE ") + battery + "%  ·  DAG " + Math.round(day * 100) + "%";
        text(cv, sub, rcx, rcy + 50 * u, 10.5f * u * ts, Theme.alpha(acc, 190), false, Paint.Align.CENTER, 0.22f);
        p.setStyle(Paint.Style.FILL);
    }

    /** Peilingen van de kinderen als pijltjes op de buitenring (noord = waar je toestel heen wijst). */
    void drawPeilingen(Canvas cv, int acc) {
        if (peil == null || !peil.ready()) return;
        float azi = peil.azimuth;
        long now = System.currentTimeMillis();
        float ringR = R * 1.14f;
        float inR = ringR + 10 * u, tipR = ringR + 24 * u, letR = ringR + 40 * u;
        for (Peilingen.Kind k : peil.kinderen) {
            float screen = k.bearing - azi;                 // 0 = recht voor je (boven)
            double a = Math.toRadians(-90 + screen);
            float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            float px = -sa, py = ca, wq = 6 * u;
            boolean stale = k.tst > 0 && now - (long) k.tst > 10 * 60 * 1000L;
            int col = stale ? Theme.alpha(k.color, 90) : k.color;
            // pijl naar buiten
            Path tri = new Path();
            tri.moveTo(rcx + ca * tipR, rcy + sa * tipR);
            tri.lineTo(rcx + ca * inR + px * wq, rcy + sa * inR + py * wq);
            tri.lineTo(rcx + ca * inR - px * wq, rcy + sa * inR - py * wq);
            tri.close();
            p.setStyle(Paint.Style.FILL);
            p.setColor(col);
            if (!stale) { p.setShadowLayer(8 * u, 0, 0, Theme.alpha(k.color, 160)); }
            cv.drawPath(tri, p);
            p.clearShadowLayer();
            // initiaal van het kind net buiten de pijl
            String init = k.name.isEmpty() ? "?" : k.name.substring(0, 1).toUpperCase(NL);
            text(cv, init, rcx + ca * letR, rcy + sa * letR + 4 * u, 12 * u * ts, col, true, Paint.Align.CENTER, 0);
        }
    }

    void box(Canvas cv, RectF b, int acc, float boot) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(Theme.alpha(Theme.deep(getContext()), (int) (55 * boot)));
        cv.drawRect(b, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.2f * u);
        p.setColor(Theme.alpha(acc, (int) (90 * boot)));
        cv.drawRect(b, p);
        // corner brackets
        float k = 9 * u;
        p.setStrokeWidth(2.2f * u);
        p.setColor(Theme.alpha(acc, (int) (255 * boot)));
        cv.drawLine(b.left, b.top, b.left + k, b.top, p); cv.drawLine(b.left, b.top, b.left, b.top + k, p);
        cv.drawLine(b.right, b.bottom, b.right - k, b.bottom, p); cv.drawLine(b.right, b.bottom, b.right, b.bottom - k, p);
        p.setStyle(Paint.Style.FILL);
    }

    void drawBoxes(Canvas cv, int acc, float boot) {
        box(cv, boxA, acc, boot);
        box(cv, boxB, acc, boot);
        float pad = 12 * u, hs = 9.5f * u * ts, l1 = 14 * u * ts, l2 = 10.5f * u * ts;
        int dim = Theme.alpha(Theme.TEXT, 150);
        // agenda
        float x = boxA.left + pad, w = boxA.width() - 2 * pad;
        text(cv, "AGENDA", x, boxA.top + pad + hs, hs, acc, false, Paint.Align.LEFT, 0.3f);
        String a1, a2;
        if (!calAccess) { a1 = "Koppelen"; a2 = "tik om te verbinden"; }
        else if (evTitle == null) { a1 = "Vrij"; a2 = "geen afspraken"; }
        else {
            long n = System.currentTimeMillis();
            if (evAllDay) { a1 = evTitle; a2 = "hele dag"; }
            else {
                a1 = fTime.format(new Date(evBegin)) + " " + evTitle;
                if (evBegin <= n) a2 = "NU · tot " + fTime.format(new Date(evEnd));
                else {
                    long mins = (evBegin - n) / 60000;
                    a2 = mins < 24 * 60 ? String.format(Locale.ROOT, "T-%02d:%02d", mins / 60, mins % 60) : "morgen";
                }
            }
        }
        text(cv, fit(a1, l1, false, w, 0), x, boxA.top + pad + hs + 8 * u + l1, l1, Theme.TEXT, false, Paint.Align.LEFT, 0);
        text(cv, fit(a2, l2, false, w, 0.05f), x, boxA.bottom - pad, l2, dim, false, Paint.Align.LEFT, 0.05f);
        // inbox
        x = boxB.left + pad; w = boxB.width() - 2 * pad;
        text(cv, "INKOMEND", x, boxB.top + pad + hs, hs, acc, false, Paint.Align.LEFT, 0.3f);
        List<NotifService.Entry> msgs = messages();
        String b1, b2;
        if (!act.notifAccess()) { b1 = "Koppelen"; b2 = "tik om te verbinden"; }
        else if (msgs.isEmpty()) { b1 = "Alles gelezen"; b2 = "geen nieuwe berichten"; }
        else {
            b1 = label(msgs.get(0)) + " ×" + msgs.get(0).count;
            b2 = msgs.size() > 1 ? label(msgs.get(1)) + " ×" + msgs.get(1).count + (msgs.size() > 2 ? "  +" + (msgs.size() - 2) : "")
                    : (msgs.get(0).title != null ? msgs.get(0).title : "");
        }
        text(cv, fit(b1, l1, false, w, 0), x, boxB.top + pad + hs + 8 * u + l1, l1, Theme.TEXT, false, Paint.Align.LEFT, 0);
        text(cv, fit(b2, l2, false, w, 0.05f), x, boxB.bottom - pad, l2, dim, false, Paint.Align.LEFT, 0.05f);
    }

    List<NotifService.Entry> messages() {
        ArrayList<NotifService.Entry> r = new ArrayList<>();
        for (NotifService.Entry e : NotifService.entries) if (e.message) r.add(e);
        return r;
    }

    String label(NotifService.Entry e) {
        Apps.Item it = Apps.byPkg(e.pkg);
        return (it != null ? it.label : e.pkg).toUpperCase(NL);
    }

    void drawBar(Canvas cv, int acc, int deep, float boot) {
        computeBar();
        // frame: dunne omlijsting met hoekhaken, in de stijl van de kaders
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1f * u);
        p.setColor(Theme.alpha(acc, (int) (45 * boot)));
        cv.drawRect(barRect, p);
        float k = 10 * u;
        p.setStrokeWidth(2f * u);
        p.setColor(Theme.alpha(acc, (int) (150 * boot)));
        cv.drawLine(barRect.left, barRect.top, barRect.left + k, barRect.top, p);
        cv.drawLine(barRect.left, barRect.top, barRect.left, barRect.top + k, p);
        cv.drawLine(barRect.right, barRect.bottom, barRect.right - k, barRect.bottom, p);
        cv.drawLine(barRect.right, barRect.bottom, barRect.right, barRect.bottom - k, p);
        p.setStyle(Paint.Style.FILL);

        int n = barItems.size();
        if (n == 0) {
            text(cv, "HOUD EEN APP VAST → VASTZETTEN IN DE BALK", barRect.centerX(), barRect.centerY() + 4 * u * ts,
                    8.5f * u * ts, Theme.alpha(acc, (int) (120 * boot)), false, Paint.Align.CENTER, 0.1f);
            return;
        }
        boolean color = prefs.getInt("icons", 0) == 1;
        cv.save();
        cv.clipRect(barRect);
        for (int i = 0; i < n; i++) {
            float cx = barCenterX(i);
            if (cx < barRect.left - barPitch || cx > barRect.right + barPitch) continue;
            Apps.Item it = barItems.get(i);
            Hex.draw(cv, it, cx, barCenterY, barIcon, color, acc, deep, 0, (int) (255 * boot));
            Hex.badge(cv, cx + barIcon * 0.34f, barCenterY - barIcon * 0.32f, 7.5f * u,
                    NotifService.count(it.pkg), Theme.second(getContext()), tp);
        }
        cv.restore();
        // subtiele rand-vervaging als er meer apps zijn dan passen
        if (barMaxScroll > 0) {
            if (barScroll > 1) edgeFade(cv, barRect.left, true);
            if (barScroll < barMaxScroll - 1) edgeFade(cv, barRect.right, false);
        }
    }

    void edgeFade(Canvas cv, float x, boolean left) {
        float w = 16 * u;
        p.setShader(new LinearGradient(left ? x : x, barCenterY, left ? x + w : x - w, barCenterY,
                Theme.BG, 0x00000000, Shader.TileMode.CLAMP));
        cv.drawRect(left ? x : x - w, barRect.top + 1, left ? x + w : x, barRect.bottom - 1, p);
        p.setShader(null);
    }

    void drawArc(Canvas cv, int acc, int deep, float boot, long t) {
        String lab = prefs.getInt("arcMode", 0) == 1 ? "BAAN // VASTGEZET" : "BAAN // MEEST GEBRUIKT";
        text(cv, lab, W / 2f, arcLabelY, 10 * u * ts, Theme.alpha(acc, 150), false, Paint.Align.CENTER, 0.35f);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.2f * u);
        p.setPathEffect(dashes);
        p.setColor(Theme.alpha(acc, 70));
        cv.drawPath(arcGuide, p);
        p.setPathEffect(null);
        // brackets framing the arc area
        float by = arcTop - iconH * 0.75f, k = 14 * u;
        p.setStrokeWidth(2 * u);
        p.setColor(Theme.alpha(acc, 160));
        cv.drawLine(6 * u, by, 6 * u + k, by, p); cv.drawLine(6 * u, by, 6 * u, by + k, p);
        cv.drawLine(W - 6 * u, by, W - 6 * u - k, by, p); cv.drawLine(W - 6 * u, by, W - 6 * u, by + k, p);
        p.setStyle(Paint.Style.FILL);

        List<Apps.Item> list = Apps.arc;
        int n = list.size();
        if (n == 0) {
            text(cv, "APPS LADEN…", W / 2f, arcTop, 12 * u, Theme.alpha(acc, 160), false, Paint.Align.CENTER, 0.3f);
            return;
        }
        boolean color = prefs.getInt("icons", 0) == 1;
        float pulse = effects() ? 0.85f + 0.15f * (float) Math.sin(t / 420.0) : 1f;
        // draw from the edges inward so the focused icon is on top
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        java.util.Arrays.sort(order, new java.util.Comparator<Integer>() {
            @Override public int compare(Integer a, Integer b) { return Float.compare(Math.abs(rel(b)), Math.abs(rel(a))); }
        });
        for (int idx : order) {
            float r = rel(idx);
            float ang = r * STEP;
            if (Math.abs(ang) > MAXA + STEP * 0.5f) continue;
            float edge = Math.min(1f, Math.abs(ang) / (MAXA + STEP * 0.5f));
            int alpha = (int) (255 * boot * (1 - edge * edge * 0.9f));
            float focus = Math.max(0, 1 - Math.abs(r));
            float hh = iconH * (1 + 0.36f * focus);
            float x = acx + (float) Math.sin(ang) * AR;
            float y = acy - (float) Math.cos(ang) * AR + (1 - boot) * 60 * u;
            Apps.Item it = list.get(idx);
            Hex.draw(cv, it, x, y, hh, color, acc, deep, focus * pulse, alpha);
            Hex.badge(cv, x + hh * 0.36f, y - hh * 0.34f, 8.5f * u, NotifService.count(it.pkg), Theme.second(getContext()), tp);
            float ls = (9.5f + 1.5f * focus) * u * ts;
            String name = fit(it.label.toUpperCase(NL), ls, focus > 0.5f, 92 * u, 0.08f);
            text(cv, name, x, y + hh * 0.5f + ls + 4 * u, ls, Theme.alpha(focus > 0.5f ? Theme.TEXT : acc, alpha), focus > 0.5f, Paint.Align.CENTER, 0.08f);
        }
    }

    void drawCmd(Canvas cv, int acc) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(Theme.alpha(0xFF000000, 120));
        cv.drawRect(cmd, p);
        if (cmdShader == null || cmdShaderColor != acc) {
            cmdShader = new LinearGradient(cmd.left, 0, cmd.right, 0, Theme.alpha(acc, 40), Theme.alpha(acc, 0), Shader.TileMode.CLAMP);
            cmdShaderColor = acc;
        }
        p.setShader(cmdShader);
        cv.drawRect(cmd, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.3f * u);
        p.setColor(Theme.alpha(acc, 190));
        cv.drawRect(cmd, p);
        p.setStyle(Paint.Style.FILL);
        float y = cmd.centerY() + 5 * u * ts;
        text(cv, ">_", cmd.left + 16 * u, y, 15 * u * ts, acc, true, Paint.Align.LEFT, 0);
        boolean blink = (System.currentTimeMillis() / 530) % 2 == 0;
        text(cv, "zoek app" + (blink ? "▍" : " "), cmd.left + 46 * u, y, 14 * u * ts, Theme.alpha(Theme.TEXT, 170), false, Paint.Align.LEFT, 0);
        text(cv, "↑ ALLE APPS", cmd.right - 14 * u, y - 1 * u, 10 * u * ts, Theme.alpha(acc, 170), false, Paint.Align.RIGHT, 0.15f);
    }

    boolean chipVisible() { return peil != null && Peilingen.on(getContext()); }

    /** Kleine chip linksboven: tik om het meld-interval van de kinderen te wisselen. */
    void drawIntChip(Canvas cv, int acc) {
        if (!chipVisible()) return;
        p.setStyle(Paint.Style.FILL);
        p.setColor(Theme.alpha(Theme.deep(getContext()), 150));
        cv.drawRoundRect(intChip, 8 * u, 8 * u, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.3f * u);
        p.setColor(Theme.alpha(acc, 190));
        cv.drawRoundRect(intChip, 8 * u, 8 * u, p);
        p.setStyle(Paint.Style.FILL);
        text(cv, "↻ " + peil.childInterval + "m", intChip.centerX(), intChip.centerY() + 4.5f * u * ts,
                12 * u * ts, acc, true, Paint.Align.CENTER, 0.05f);
    }

    void drawGear(Canvas cv, int acc) {
        float cx = gear.centerX(), cy = gear.centerY(), r = 9 * u;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.6f * u);
        p.setColor(Theme.alpha(acc, 200));
        cv.drawPath(Hex.shape(cx, cy, r * 2.2f), p);
        p.setStyle(Paint.Style.FILL);
        cv.drawCircle(cx, cy, r * 0.32f, p);
    }

    // ----- onboarding
    static final String[][] TOUR = {
            {"WELKOM BIJ BAAN", "Je startscherm als instrumentpaneel. De ring toont de tijd, je batterij (binnen) en hoe ver de dag is (buiten). Tik op de ring voor je wekker."},
            {"DE BAAN", "Je meest gebruikte apps liggen op een boog onder je duim. Veeg links of rechts om te draaien en tik om te openen. Houd een app vast voor opties, zoals vastzetten."},
            {"ALLES VINDEN", "Veeg omhoog of tik op de zoekbalk voor al je apps; typ een paar letters en druk op Enter. Instellingen: het zeshoekje rechtsboven, of houd het scherm vast."}};

    void drawTour(Canvas cv, int acc) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xD8000000);
        cv.drawRect(0, 0, W, H, p);
        RectF b = new RectF(24 * u, H * 0.30f, W - 24 * u, H * 0.66f);
        box(cv, b, acc, 1);
        String[] pg = TOUR[tour];
        float x = b.left + 20 * u;
        text(cv, (tour + 1) + " / " + TOUR.length, b.right - 20 * u, b.top + 30 * u, 11 * u, Theme.alpha(acc, 170), false, Paint.Align.RIGHT, 0.2f);
        text(cv, pg[0], x, b.top + 64 * u, 19 * u * Math.min(ts, 1.15f), acc, true, Paint.Align.LEFT, 0.12f);
        float y = b.top + 104 * u;
        float sz = 14 * u * Math.min(ts, 1.15f);
        for (String line : wrapText(pg[1], sz, b.width() - 40 * u)) {
            text(cv, line, x, y, sz, Theme.TEXT, false, Paint.Align.LEFT, 0);
            y += sz * 1.5f;
        }
        text(cv, tour == TOUR.length - 1 ? "TIK OM TE BEGINNEN" : "TIK OM VERDER TE GAAN", W / 2f, b.bottom - 22 * u, 11 * u, acc, false, Paint.Align.CENTER, 0.25f);
        text(cv, "SLA OVER", W - 24 * u, b.top - 18 * u, 11 * u, Theme.alpha(Theme.TEXT, 170), false, Paint.Align.RIGHT, 0.25f);
    }

    ArrayList<String> wrapText(String s, float size, float max) {
        tp.setTypeface(Theme.mono);
        tp.setTextSize(size);
        ArrayList<String> out = new ArrayList<>();
        String line = "";
        for (String w : s.split(" ")) {
            String cand = line.isEmpty() ? w : line + " " + w;
            if (tp.measureText(cand) > max && !line.isEmpty()) { out.add(line); line = w; } else line = cand;
        }
        if (!line.isEmpty()) out.add(line);
        return out;
    }

    void tourNext(boolean skip) {
        if (skip || ++tour >= TOUR.length) {
            tour = -1;
            prefs.edit().putBoolean("tourDone", true).apply();
            act.afterTour();
            kick();
        }
        invalidate();
    }

    // ===================================================================== touch
    int region(float x, float y) {
        if (chipVisible() && intChip.contains(x, y)) return R_INT;
        if (gear.contains(x, y)) return R_GEAR;
        if (cmd.contains(x, y)) return R_CMD;
        if (boxA.contains(x, y)) return R_AGENDA;
        if (boxB.contains(x, y)) return R_INBOX;
        if (barRect.contains(x, y)) return R_BAR;
        float dx = x - rcx, dy = y - rcy;
        if (dx * dx + dy * dy < (R * 1.2f) * (R * 1.2f)) return R_RING;
        if (y > arcLabelY - 10 * u && y < cmd.top) return R_ARC;
        return R_NONE;
    }

    /** Index of the arc icon under (x,y), or -1. */
    int hitArc(float x, float y) {
        int n = Apps.arc.size(), best = -1;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            float r = rel(i), ang = r * STEP;
            if (Math.abs(ang) > MAXA + STEP * 0.3f) continue;
            float focus = Math.max(0, 1 - Math.abs(r));
            float hh = iconH * (1 + 0.36f * focus);
            float ix = acx + (float) Math.sin(ang) * AR, iy = acy - (float) Math.cos(ang) * AR;
            float d = (x - ix) * (x - ix) + (y - iy) * (y - iy);
            if (d < (hh * 0.62f) * (hh * 0.62f) && d < bestD) { bestD = d; best = i; }
        }
        return best;
    }

    final Runnable longPress = new Runnable() {
        @Override public void run() {
            if (moved || tour >= 0) return;
            longFired = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (downRegion == R_ARC) {
                int i = hitArc(downX, downY);
                if (i >= 0) { act.appOptions(Apps.arc.get(i)); return; }
            }
            if (downRegion == R_BAR) {
                int i = hitBar(downX, downY);
                if (i >= 0) { act.appOptions(barItems.get(i)); return; }
            }
            act.openSettings();
        }
    };

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        if (vt == null) vt = VelocityTracker.obtain();
        vt.addMovement(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = x; downY = y; downT = SystemClock.uptimeMillis();
                moved = longFired = swiped = dragging = false;
                downRegion = region(x, y);
                barDragging = false;
                if (flinging || snapping) { flinging = false; snapping = true; }
                h.postDelayed(longPress, 480);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = x - downX, dy = y - downY;
                if (!moved && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                    moved = true;
                    h.removeCallbacks(longPress);
                    if (tour < 0 && downRegion == R_ARC && Math.abs(dx) > Math.abs(dy)) {
                        dragging = true; snapping = false; flinging = false; kick();
                    } else if (tour < 0 && downRegion == R_BAR && barMaxScroll > 0 && Math.abs(dx) > Math.abs(dy)) {
                        barDragging = true;
                    }
                }
                if (barDragging) {
                    barScroll -= (x - lastX);
                    if (barScroll < 0) barScroll = 0;
                    if (barScroll > barMaxScroll) barScroll = barMaxScroll;
                    lastX = x;
                    invalidate();
                } else if (dragging) {
                    float pxPerItem = AR * STEP;
                    scroll -= (x - lastX) / pxPerItem;
                    if (!wrap()) {
                        int n = Apps.arc.size();
                        if (scroll < -0.4f) scroll = -0.4f;
                        if (scroll > n - 0.6f) scroll = n - 0.6f;
                    }
                    lastX = x;
                    invalidate();
                } else if (moved && !swiped && tour < 0) {
                    if (dy < -70 * u && Math.abs(dy) > Math.abs(dx) * 1.3f) { swiped = true; act.openDrawer(false); }
                    else if (dy > 70 * u && Math.abs(dy) > Math.abs(dx) * 1.3f && downY < H * 0.55f) { swiped = true; act.expandShade(); }
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                h.removeCallbacks(longPress);
                if (barDragging) { barDragging = false; vt.recycle(); vt = null; return true; }
                if (dragging) {
                    vt.computeCurrentVelocity(1000);
                    vel = -vt.getXVelocity() / (AR * STEP);
                    vel = Math.max(-14, Math.min(14, vel));
                    dragging = false;
                    if (Math.abs(vel) > 1.2f) flinging = true; else snapping = true;
                    kick();
                } else if (!moved && !longFired) {
                    tap(x, y);
                }
                vt.recycle(); vt = null;
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                h.removeCallbacks(longPress);
                barDragging = false;
                if (dragging) { dragging = false; snapping = true; kick(); }
                if (vt != null) { vt.recycle(); vt = null; }
                return true;
        }
        return true;
    }

    void tap(float x, float y) {
        if (tour >= 0) {
            boolean skip = y < H * 0.30f && x > W * 0.55f;
            tourNext(skip);
            return;
        }
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        click(region(x, y), x, y);
    }

    void click(int reg, float x, float y) {
        switch (reg) {
            case R_GEAR: act.openSettings(); break;
            case R_INT: {
                int next = peil.nextInterval();
                peil.pushInterval(next);
                act.toast("Kinderen melden nu elke " + next + " min");
                invalidate();
                break;
            }
            case R_CMD: act.openDrawer(true); break;
            case R_RING: act.startSafe(new Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)); break;
            case R_AGENDA:
                if (!calAccess) act.askCalendar();
                else {
                    Intent i = new Intent(Intent.ACTION_VIEW).setData(android.provider.CalendarContract.CONTENT_URI.buildUpon()
                            .appendPath("time").appendPath(String.valueOf(evTitle != null ? evBegin : System.currentTimeMillis())).build());
                    act.startSafe(i);
                }
                break;
            case R_INBOX:
                if (!act.notifAccess()) act.askNotifAccess();
                else {
                    List<NotifService.Entry> m = messages();
                    if (!m.isEmpty()) act.openEntry(m.get(0));
                    else act.expandShade();
                }
                break;
            case R_ARC: {
                int i = hitArc(x, y);
                if (i < 0) break;
                if (Math.abs(rel(i)) > 0.5f && prefs.getBoolean("centerFirst", false)) {
                    scroll = scroll + rel(i); snapping = true; kick();
                } else {
                    launchArc(i);
                }
                break;
            }
            case R_BAR: {
                int i = hitBar(x, y);
                if (i >= 0) launchBar(i);
                break;
            }
        }
    }

    void launchArc(int i) {
        if (i < 0 || i >= Apps.arc.size()) return;
        if (!Apps.launch(getContext(), Apps.arc.get(i))) act.toast("Kan app niet openen");
    }

    // ===================================================================== accessibility
    final ArrayList<int[]> vIds = new ArrayList<>();

    @Override
    public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return provider;
    }

    Rect boundsFor(int id) {
        RectF r = new RectF();
        switch (id) {
            case R_RING: r.set(rcx - R, rcy - R, rcx + R, rcy + R); break;
            case R_AGENDA: r.set(boxA); break;
            case R_INBOX: r.set(boxB); break;
            case R_CMD: r.set(cmd); break;
            case R_GEAR: r.set(gear); break;
            case R_INT: r.set(intChip); break;
            default: {
                if (id >= R_BARID) {
                    computeBar();
                    float cx = barCenterX(id - R_BARID);
                    r.set(cx - barIcon / 2, barCenterY - barIcon / 2, cx + barIcon / 2, barCenterY + barIcon / 2);
                } else {
                    int i = id - R_ARC;
                    float ang = rel(i) * STEP;
                    float x = acx + (float) Math.sin(ang) * AR, y = acy - (float) Math.cos(ang) * AR;
                    r.set(x - iconH / 2, y - iconH / 2, x + iconH / 2, y + iconH / 2);
                }
            }
        }
        Rect out = new Rect();
        r.round(out);
        return out;
    }

    String describe(int id) {
        switch (id) {
            case R_RING: {
                StringBuilder s = new StringBuilder("Klok " + fTime.format(new Date())
                        + (battery >= 0 ? ", batterij " + battery + " procent" : ""));
                if (peil != null && peil.ready()) for (Peilingen.Kind k : peil.kinderen)
                    s.append(". ").append(k.name).append(" ").append(Peilingen.afstand(k.dist))
                            .append(" naar het ").append(Peilingen.kompas(k.bearing));
                s.append(". Tik voor wekker.");
                return s.toString();
            }
            case R_AGENDA: return !calAccess ? "Agenda koppelen" : evTitle == null ? "Agenda: geen afspraken" : "Volgende afspraak: " + evTitle + " om " + fTime.format(new Date(evBegin));
            case R_INBOX: {
                List<NotifService.Entry> m = messages();
                return !act.notifAccess() ? "Berichten koppelen" : m.isEmpty() ? "Geen nieuwe berichten" : "Nieuwe berichten van " + label(m.get(0));
            }
            case R_CMD: return "Zoek een app";
            case R_GEAR: return "Instellingen";
            case R_INT: return "Meld-interval kinderen: elke " + peil.childInterval + " minuten. Tik om te wisselen.";
            default: {
                if (id >= R_BARID) {
                    int i = id - R_BARID;
                    return i < barItems.size() ? barItems.get(i).label + ", vastgezet in de balk" : "";
                }
                int i = id - R_ARC;
                return i < Apps.arc.size() ? Apps.arc.get(i).label : "";
            }
        }
    }

    List<Integer> visibleIds() {
        ArrayList<Integer> l = new ArrayList<>();
        l.add(R_GEAR); l.add(R_RING); l.add(R_AGENDA); l.add(R_INBOX);
        if (chipVisible()) l.add(R_INT);
        for (int i = 0; i < barItems.size(); i++) l.add(R_BARID + i);
        for (int i = 0; i < Apps.arc.size(); i++) if (Math.abs(rel(i) * STEP) <= MAXA) l.add(R_ARC + i);
        l.add(R_CMD);
        return l;
    }

    final AccessibilityNodeProvider provider = new AccessibilityNodeProvider() {
        @Override
        public AccessibilityNodeInfo createAccessibilityNodeInfo(int id) {
            if (id == View.NO_ID) {
                AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain(HudView.this);
                onInitializeAccessibilityNodeInfo(info);
                for (int v : visibleIds()) info.addChild(HudView.this, v);
                return info;
            }
            AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain(HudView.this, id);
            info.setPackageName(getContext().getPackageName());
            info.setClassName("android.widget.Button");
            info.setParent(HudView.this);
            info.setContentDescription(describe(id));
            Rect b = boundsFor(id);
            info.setBoundsInParent(b);
            int[] loc = new int[2];
            getLocationOnScreen(loc);
            b.offset(loc[0], loc[1]);
            info.setBoundsInScreen(b);
            info.setVisibleToUser(true);
            info.setEnabled(true);
            info.setClickable(true);
            info.addAction(AccessibilityNodeInfo.ACTION_CLICK);
            if (id >= R_ARC) info.addAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);
            return info;
        }

        @Override
        public boolean performAction(int id, int action, Bundle args) {
            if (id == View.NO_ID) return performAccessibilityAction(action, args);
            if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                if (id >= R_BARID) launchBar(id - R_BARID);
                else if (id >= R_ARC) launchArc(id - R_ARC);
                else click(id, 0, 0);
                return true;
            }
            if (action == AccessibilityNodeInfo.ACTION_LONG_CLICK) {
                if (id >= R_BARID && id - R_BARID < barItems.size()) { act.appOptions(barItems.get(id - R_BARID)); return true; }
                if (id >= R_ARC && id - R_ARC < Apps.arc.size()) { act.appOptions(Apps.arc.get(id - R_ARC)); return true; }
            }
            return false;
        }
    };

    int hovered = View.NO_ID;

    @Override
    protected boolean dispatchHoverEvent(MotionEvent e) {
        android.view.accessibility.AccessibilityManager am =
                (android.view.accessibility.AccessibilityManager) getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null || !am.isTouchExplorationEnabled()) return super.dispatchHoverEvent(e);
        int id = View.NO_ID;
        int reg = region(e.getX(), e.getY());
        if (reg == R_ARC) { int i = hitArc(e.getX(), e.getY()); if (i >= 0) id = R_ARC + i; }
        else if (reg == R_BAR) { int i = hitBar(e.getX(), e.getY()); if (i >= 0) id = R_BARID + i; }
        else if (reg != R_NONE) id = reg;
        if (id != hovered) {
            if (hovered != View.NO_ID) sendVirtual(hovered, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT);
            if (id != View.NO_ID) sendVirtual(id, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER);
            hovered = id;
        }
        return id != View.NO_ID || super.dispatchHoverEvent(e);
    }

    void sendVirtual(int id, int type) {
        AccessibilityEvent ev = AccessibilityEvent.obtain(type);
        ev.setPackageName(getContext().getPackageName());
        ev.setClassName("android.widget.Button");
        ev.setSource(this, id);
        ev.setContentDescription(describe(id));
        if (getParent() != null) getParent().requestSendAccessibilityEvent(this, ev);
    }
}
