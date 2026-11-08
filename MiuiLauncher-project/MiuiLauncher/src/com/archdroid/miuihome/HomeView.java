package com.archdroid.miuihome;

import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.IBinder;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.Scroller;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * The whole MIUI-style home screen in one canvas-drawn view: clock widget, paged 4-column
 * icon grid with rounded icons, square page dots and a dock. No child views, no layouts.
 */
public class HomeView extends View {
    public interface Host {
        void launch(App a);
        void appMenu(App a);
        void emptyMenu();
    }

    private static final int COLS = 4;

    private final Host host;
    private final float d = getResources().getDisplayMetrics().density;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG); // icon labels
    private final Paint cp = new Paint(Paint.ANTI_ALIAS_FLAG);     // big clock
    private final Paint dp = new Paint(Paint.ANTI_ALIAS_FLAG);     // date lines
    private final RectF rf = new RectF();
    private final Handler h = new Handler();
    private final Scroller scroller;
    private final ArrayList<ArrayList<App>> pages = new ArrayList<ArrayList<App>>();
    private final ArrayList<App> dock = new ArrayList<App>();
    private ArrayList<App> apps = new ArrayList<App>();
    private int loadGen;

    private int w, hgt, statusH, rows0 = 1, rows = 1;
    private int touchSlop, minFling, maxFling;
    private float rowH, cellW, iconS, gridBottom, dockTop, dotsY, clockBottom;
    private float scrollPx, downX, lastX;
    private String timeStr = "", dateStr = "", dayStr = "";
    private App down;
    private boolean dragging, longFired;
    private VelocityTracker vt;

    private final Runnable longRun = new Runnable() {
        public void run() {
            if (dragging) return;
            longFired = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (down != null) {
                App a = down;
                down = null;
                invalidate();
                host.appMenu(a);
            } else {
                host.emptyMenu();
            }
        }
    };

    public HomeView(Context c, Host host) {
        super(c);
        this.host = host;
        scroller = new Scroller(c, new DecelerateInterpolator(1.4f));
        ViewConfiguration vc = ViewConfiguration.get(c);
        touchSlop = vc.getScaledTouchSlop();
        maxFling = vc.getScaledMaximumFlingVelocity();
        minFling = (int) (250 * d);

        tp.setTextAlign(Paint.Align.CENTER);
        tp.setTextSize(11 * d);
        tp.setColor(0xFFFFFFFF);
        tp.setShadowLayer(2 * d, 0, 1 * d, 0xB0000000);

        cp.setColor(0xFFFFFFFF);
        cp.setTextAlign(Paint.Align.RIGHT);
        cp.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        cp.setTextSize(64 * d);
        cp.setShadowLayer(3 * d, 0, 1 * d, 0x40000000);

        dp.setColor(0xFFFFFFFF);
        dp.setTextAlign(Paint.Align.RIGHT);
        dp.setTextSize(15 * d);
        dp.setShadowLayer(2 * d, 0, 1 * d, 0x60000000);

        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        statusH = id > 0 ? getResources().getDimensionPixelSize(id) : (int) (25 * d);
        iconS = 54 * d;
        rowH = 80 * d;
        tickClock();
    }

    // ------------------------------------------------------------------ data

    public void tickClock() {
        Context c = getContext();
        Date now = new Date();
        Locale l = Locale.getDefault();
        timeStr = new SimpleDateFormat(DateFormat.is24HourFormat(c) ? "HH:mm" : "h:mm", l).format(now);
        dateStr = new SimpleDateFormat("yyyy/MM/dd", l).format(now);
        dayStr = new SimpleDateFormat("EEEE", l).format(now);
        invalidate();
    }

    public void reload() {
        final int gen = ++loadGen;
        new Thread(new Runnable() {
            public void run() {
                final ArrayList<App> a = new ArrayList<App>();
                final ArrayList<App> dk = new ArrayList<App>();
                try { load(a, dk); } catch (Throwable t) { }
                post(new Runnable() {
                    public void run() {
                        if (gen == loadGen) setData(a, dk);
                    }
                });
            }
        }).start();
    }

    private ComponentName resolve(PackageManager pm, Intent in) {
        ResolveInfo ri = pm.resolveActivity(in, PackageManager.MATCH_DEFAULT_ONLY);
        if (ri != null && ri.activityInfo != null && !"android".equals(ri.activityInfo.packageName)) {
            return new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
        }
        List<ResolveInfo> l = pm.queryIntentActivities(in, 0);
        if (l != null && !l.isEmpty()) {
            return new ComponentName(l.get(0).activityInfo.packageName, l.get(0).activityInfo.name);
        }
        return null;
    }

    private App make(PackageManager pm, ActivityInfo ai, int S) {
        App a = new App();
        CharSequence cs = ai.loadLabel(pm);
        a.label = cs == null ? ai.packageName : cs.toString();
        a.pkg = ai.packageName;
        a.icon = makeIcon(ai.loadIcon(pm), S);
        return a;
    }

    private void load(ArrayList<App> out, ArrayList<App> dk) throws Exception {
        Context ctx = getContext();
        PackageManager pm = ctx.getPackageManager();
        int S = (int) iconS;

        // dock: phone, contacts, browser, messaging (whatever the ROM provides)
        Intent[] di = new Intent[4];
        di[0] = new Intent(Intent.ACTION_DIAL);
        di[1] = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CONTACTS);
        di[2] = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER);
        di[3] = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING);
        HashSet<String> dockKeys = new HashSet<String>();
        for (int i = 0; i < 4; i++) {
            ComponentName cn = resolve(pm, di[i]);
            if (cn == null) continue;
            try {
                App a = make(pm, pm.getActivityInfo(cn, 0), S);
                a.intent = (i == 0) ? new Intent(Intent.ACTION_DIAL)
                        : new Intent(Intent.ACTION_MAIN).setComponent(cn);
                dk.add(a);
                dockKeys.add(cn.getPackageName() + "/" + cn.getClassName());
            } catch (Exception e) { }
        }

        // everything else, alphabetical, flowed onto pages like MIUI (no app drawer)
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris = pm.queryIntentActivities(main, 0);
        for (ResolveInfo ri : ris) {
            ActivityInfo ai = ri.activityInfo;
            if (ai.packageName.equals(ctx.getPackageName())) continue;
            if (dockKeys.contains(ai.packageName + "/" + ai.name)) continue;
            App a = make(pm, ai, S);
            a.intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(new ComponentName(ai.packageName, ai.name));
            out.add(a);
        }
        Collections.sort(out, new Comparator<App>() {
            public int compare(App x, App y) { return x.label.compareToIgnoreCase(y.label); }
        });
    }

    /** MIUI look: transparent icons sit on a light rounded plate, square icons just get round corners. */
    private Bitmap makeIcon(Drawable dr, int S) {
        Bitmap content = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(content);
        boolean opaque = false;
        if (dr instanceof BitmapDrawable) {
            Bitmap src = ((BitmapDrawable) dr).getBitmap();
            if (src != null && src.getWidth() > 4 && src.getHeight() > 4) {
                int sw = src.getWidth(), sh = src.getHeight();
                opaque = Color.alpha(src.getPixel(1, 1)) > 245
                        && Color.alpha(src.getPixel(sw - 2, 1)) > 245
                        && Color.alpha(src.getPixel(1, sh - 2)) > 245
                        && Color.alpha(src.getPixel(sw - 2, sh - 2)) > 245;
            }
        }
        if (opaque) {
            dr.setBounds(0, 0, S, S);
            dr.draw(c);
        } else {
            Paint pl = new Paint(Paint.ANTI_ALIAS_FLAG);
            pl.setShader(new LinearGradient(0, 0, 0, S, 0xFFFFFFFF, 0xFFD5DAE3, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, S, S, pl);
            int in = (int) (S * 0.13f);
            dr.setBounds(in, in, S - in, S - in);
            dr.draw(c);
        }
        Bitmap out = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
        Paint m = new Paint(Paint.ANTI_ALIAS_FLAG);
        m.setShader(new BitmapShader(content, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        float rad = S * 0.22f;
        new Canvas(out).drawRoundRect(new RectF(0, 0, S, S), rad, rad, m);
        content.recycle();
        return out;
    }

    private void setData(ArrayList<App> a, ArrayList<App> dk) {
        apps = a;
        dock.clear();
        dock.addAll(dk);
        paginate();
        invalidate();
    }

    private void shorten() {
        if (cellW <= 0) return;
        float max = cellW - 8 * d;
        for (App a : apps) a.shortLabel = TextUtils.ellipsize(a.label, tp, max, TextUtils.TruncateAt.END).toString();
        for (App a : dock) a.shortLabel = TextUtils.ellipsize(a.label, tp, max, TextUtils.TruncateAt.END).toString();
    }

    private void paginate() {
        pages.clear();
        shorten();
        ArrayList<App> cur = new ArrayList<App>();
        int cap = rows0 * COLS;
        for (App a : apps) {
            if (cur.size() >= cap) {
                pages.add(cur);
                cur = new ArrayList<App>();
                cap = rows * COLS;
            }
            cur.add(a);
        }
        pages.add(cur);
        clampScroll();
    }

    // ---------------------------------------------------------------- layout

    @Override protected void onSizeChanged(int nw, int nh, int ow, int oh) {
        int oldPage = ow > 0 ? Math.round(scrollPx / ow) : 0;
        w = nw;
        hgt = nh;
        cellW = w / (float) COLS;
        dockTop = hgt - 86 * d;
        dotsY = dockTop - 14 * d;
        gridBottom = dotsY - 8 * d;
        clockBottom = 145 * d;
        rows0 = (int) ((gridBottom - clockBottom) / rowH);
        if (rows0 < 1) rows0 = 1;
        rows = (int) ((gridBottom - (statusH + 6 * d)) / rowH);
        if (rows < 1) rows = 1;
        paginate();
        scrollPx = Math.min(oldPage, pages.size() - 1) * w;
    }

    private float maxScroll() {
        return Math.max(0, (pages.size() - 1) * w);
    }

    private void clampScroll() {
        scrollPx = Math.max(0, Math.min(scrollPx, maxScroll()));
    }

    // ---------------------------------------------------------------- drawing

    @Override protected void onDraw(Canvas c) {
        int n = pages.size();
        for (int i = 0; i < n; i++) {
            float x0 = i * w - scrollPx;
            if (x0 <= -w || x0 >= w) continue;
            if (i == 0) drawClock(c, x0);
            ArrayList<App> pg = pages.get(i);
            int nr = (i == 0) ? rows0 : rows;
            float top0 = gridBottom - nr * rowH;
            for (int k = 0; k < pg.size(); k++) {
                drawApp(c, pg.get(k), x0 + (k % COLS) * cellW, top0 + (k / COLS) * rowH + 4 * d);
            }
        }
        for (int k = 0; k < dock.size() && k < COLS; k++) drawApp(c, dock.get(k), k * cellW, dockTop);
        drawDots(c);
    }

    private void drawClock(Canvas c, float x0) {
        float right = x0 + w - 20 * d;
        c.drawText(timeStr, right, statusH + 68 * d, cp);
        c.drawText(dateStr, right, statusH + 96 * d, dp);
        c.drawText(dayStr, right, statusH + 111 * d, dp);
    }

    private void drawApp(Canvas c, App a, float cellX, float top) {
        float cx = cellX + cellW / 2f;
        float left = cx - iconS / 2f;
        float rad = iconS * 0.22f;
        boolean pressed = (a == down);
        c.save();
        if (pressed) c.scale(0.9f, 0.9f, cx, top + iconS / 2f);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x30000000);
        rf.set(left + 1 * d, top + 2.5f * d, left + iconS - 1 * d, top + iconS + 2.5f * d);
        c.drawRoundRect(rf, rad, rad, p);
        if (a.icon != null) {
            rf.set(left, top, left + iconS, top + iconS);
            c.drawBitmap(a.icon, null, rf, bp);
        }
        if (pressed) {
            p.setColor(0x55000000);
            rf.set(left, top, left + iconS, top + iconS);
            c.drawRoundRect(rf, rad, rad, p);
        }
        c.restore();
        c.drawText(a.shortLabel, cx, top + iconS + 15 * d, tp);
    }

    private void drawDots(Canvas c) {
        int n = pages.size();
        if (n < 2) return;
        float sq = 6 * d, gap = 7 * d;
        float x = (w - (n * sq + (n - 1) * gap)) / 2f;
        float y = dotsY - sq / 2f;
        int cur = Math.round(scrollPx / Math.max(1, w));
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < n; i++) {
            p.setColor(i == cur ? 0xFFFFFFFF : 0x66FFFFFF);
            rf.set(x, y, x + sq, y + sq);
            c.drawRoundRect(rf, 1.5f * d, 1.5f * d, p);
            x += sq + gap;
        }
    }

    // ----------------------------------------------------------------- paging

    private void updateWallpaper() {
        int n = pages.size();
        IBinder tok = getWindowToken();
        if (tok == null || n < 2 || w == 0) return;
        try {
            float f = scrollPx / (w * (n - 1));
            WallpaperManager wm = WallpaperManager.getInstance(getContext());
            wm.setWallpaperOffsetSteps(1f / (n - 1), 0f);
            wm.setWallpaperOffsets(tok, Math.max(0f, Math.min(1f, f)), 0.5f);
        } catch (Throwable t) { }
    }

    private void animateTo(int page) {
        page = Math.max(0, Math.min(page, pages.size() - 1));
        int target = page * w, cur = (int) scrollPx;
        if (target == cur) {
            scrollPx = target;
            invalidate();
            return;
        }
        scroller.startScroll(cur, 0, target - cur, 0, 300);
        postInvalidateOnAnimation();
    }

    public void goHome() {
        animateTo(0);
    }

    private void snap(float vx) {
        float cur = scrollPx / Math.max(1, w);
        int target = Math.round(cur);
        if (vx < -minFling) target = (int) Math.floor(cur) + 1;
        else if (vx > minFling) target = (int) Math.ceil(cur) - 1;
        animateTo(target);
    }

    @Override public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollPx = scroller.getCurrX();
            updateWallpaper();
            postInvalidateOnAnimation();
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateWallpaper();
    }

    // ------------------------------------------------------------------ touch

    private App hit(float x, float y) {
        if (y >= dockTop - 6 * d && y <= dockTop + iconS + 22 * d) {
            int col = (int) (x / cellW);
            return (col >= 0 && col < dock.size()) ? dock.get(col) : null;
        }
        int n = pages.size();
        if (n == 0) return null;
        int i = Math.max(0, Math.min(n - 1, Math.round(scrollPx / Math.max(1, w))));
        float lx = x - (i * w - scrollPx);
        if (lx < 0 || lx >= w) return null;
        int nr = (i == 0) ? rows0 : rows;
        float top0 = gridBottom - nr * rowH;
        if (y < top0 || y >= gridBottom) return null;
        int col = (int) (lx / cellW), row = (int) ((y - top0) / rowH);
        int idx = row * COLS + col;
        ArrayList<App> pg = pages.get(i);
        if (col >= COLS || idx >= pg.size()) return null;
        return pg.get(idx);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (vt == null) vt = VelocityTracker.obtain();
        vt.addMovement(e);
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                scroller.forceFinished(true);
                downX = x;
                lastX = x;
                dragging = false;
                longFired = false;
                down = hit(x, y);
                invalidate();
                h.removeCallbacks(longRun);
                h.postDelayed(longRun, ViewConfiguration.getLongPressTimeout());
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (!dragging && Math.abs(x - downX) > touchSlop) {
                    dragging = true;
                    down = null;
                    h.removeCallbacks(longRun);
                }
                if (dragging) {
                    float dx = lastX - x;
                    float max = maxScroll();
                    float ns = scrollPx + dx;
                    if (ns < 0 || ns > max) ns = scrollPx + dx * 0.4f;
                    scrollPx = Math.max(-w * 0.2f, Math.min(ns, max + w * 0.2f));
                    updateWallpaper();
                    invalidate();
                }
                lastX = x;
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                h.removeCallbacks(longRun);
                boolean up = e.getActionMasked() == MotionEvent.ACTION_UP;
                if (dragging) {
                    vt.computeCurrentVelocity(1000, maxFling);
                    snap(up ? vt.getXVelocity() : 0f);
                } else if (up && !longFired && down != null) {
                    host.launch(down);
                }
                down = null;
                dragging = false;
                vt.recycle();
                vt = null;
                invalidate();
                return true;
            }
            default:
                return true;
        }
    }

    @Override protected void onDetachedFromWindow() {
        h.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }
}
