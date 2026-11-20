package com.thermalhunter.ui;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.thermalhunter.core.Analyzer;
import com.thermalhunter.core.Analyzer.Entity;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Color scheme and shared UI components: used across main interface, bottom sheet, and floating window for consistency. */
final class Ui {

    // Temperature gradients: No data / Cool / Warm / Hot
    static final int[] GRAD_NONE = {0xFF64748B, 0xFF94A3B8};
    static final int[] GRAD_COOL = {0xFF2563EB, 0xFF06B6D4};
    static final int[] GRAD_WARM = {0xFFF59E0B, 0xFFF97316};
    static final int[] GRAD_HOT = {0xFFEF4444, 0xFFDB2777};

    final Context ctx;
    final boolean night;
    final int bg, card, stroke, sunken, track, fg, dim, accent, good, warn, bad, badCard;

    Ui(Context ctx) {
        this(ctx, (ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES);
    }

    Ui(Context ctx, boolean night) {
        this.ctx = ctx;
        this.night = night;
        if (night) {
            bg = 0xFF0E1013; card = 0xFF181B20; stroke = 0xFF262A31; sunken = 0xFF21252B; track = 0xFF2E333B;
            fg = 0xFFE8EAED; dim = 0xFF9AA0A6; accent = 0xFF5B8CFF;
            good = 0xFF4ADE80; warn = 0xFFFBBF24; bad = 0xFFF87171; badCard = 0xFF24181B;
        } else {
            bg = 0xFFF4F5F9; card = 0xFFFFFFFF; stroke = 0xFFE7E9F0; sunken = 0xFFF4F5F9; track = 0xFFE4E7EE;
            fg = 0xFF111827; dim = 0xFF6B7280; accent = 0xFF3B6EF5;
            good = 0xFF16A34A; warn = 0xFFD97706; bad = 0xFFDC2626; badCard = 0xFFFFF6F6;
        }
    }

    // ------------------------------------------------------------------ Basics

    int dp(float v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    LinearLayout col() {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    LinearLayout row() {
        LinearLayout l = new LinearLayout(ctx);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, -2, w);
    }

    static GradientDrawable round(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    /** Card background with subtle stroke. */
    GradientDrawable card(int radius) {
        GradientDrawable g = round(card, radius);
        g.setStroke(dp(1), stroke);
        return g;
    }

    Drawable ripple(Drawable content) {
        return new RippleDrawable(ColorStateList.valueOf(alpha(fg, 0x1F)), content, null);
    }

    static GradientDrawable gradient(int[] colors, int radius) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        g.setCornerRadius(radius);
        return g;
    }

    static int alpha(int color, int a) {
        return (color & 0x00FFFFFF) | (a << 24);
    }

    /** Tint background matching color scheme (tags, hints). */
    static int tint(int color) {
        return alpha(color, 0x24);
    }

    /** Small badge with light background and colored text. */
    TextView chip(String s, int color) {
        TextView t = text(s, 11, color, true);
        t.setPadding(dp(9), dp(3), dp(9), dp(3));
        t.setBackground(round(tint(color), dp(10)));
        return t;
    }

    /** Note block with light background and colored text (diagnostics, tips). */
    TextView note(String s, int color) {
        TextView t = text(s, 13, color, false);
        t.setLineSpacing(dp(2), 1f);
        t.setPadding(dp(12), dp(10), dp(12), dp(10));
        t.setBackground(round(tint(color), dp(12)));
        return t;
    }

    /** Data tile: title + value, used in load cards and details pages. */
    LinearLayout tile(String label, String value, int valueColor) {
        LinearLayout t = col();
        t.setPadding(dp(12), dp(10), dp(12), dp(10));
        t.setBackground(round(sunken, dp(14)));
        TextView l = text(label, 11, dim, false);
        l.setSingleLine(true);
        TextView v = text(value, 16, valueColor, true);
        v.setFontFeatureSettings("tnum");
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        t.addView(l);
        t.addView(v, lp(-2, -2, 0, dp(2), 0, 0));
        return t;
    }

    int levelColor(int level) {
        return level == Analyzer.BAD ? bad : level == Analyzer.WARN ? warn : accent;
    }

    int heatColor(double t) {
        if (t <= -999) return dim;
        return t >= 50 ? bad : t >= 42 ? warn : good;
    }

    static int[] heatGradient(double t) {
        return t <= -999 ? GRAD_NONE : t >= 50 ? GRAD_HOT : t >= 42 ? GRAD_WARM : GRAD_COOL;
    }

    // ------------------------------------------------------------------ Entity Display

    private static final Map<String, Object[]> APP_INFO = new HashMap<>(); // pkg -> {label, icon}

    Object[] appInfo(String pkg) {
        synchronized (APP_INFO) {
            Object[] cached = APP_INFO.get(pkg);
            if (cached != null) return cached;
            Object[] v = new Object[]{null, null};
            try {
                PackageManager pm = ctx.getPackageManager();
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                v[0] = pm.getApplicationLabel(ai).toString();
                v[1] = pm.getApplicationIcon(ai);
            } catch (Throwable ignored) {
            }
            APP_INFO.put(pkg, v);
            return v;
        }
    }

    String label(Entity e) {
        if (e.type == Analyzer.APP || e.type == Analyzer.LSP) {
            Object[] info = appInfo(e.pkg);
            if (info[0] != null) return (String) info[0];
        }
        return e.title;
    }

    Drawable appIcon(Entity e) {
        if (e.type == Analyzer.APP || e.type == Analyzer.LSP) return (Drawable) appInfo(e.pkg)[1];
        return null;
    }

    /** Entity avatar: uses app icon if available, otherwise colored block with first character. */
    FrameLayout avatar(int sizeDp) {
        FrameLayout box = new FrameLayout(ctx);
        ImageView icon = new ImageView(ctx);
        box.addView(icon, new FrameLayout.LayoutParams(-1, -1));
        TextView letter = text("", sizeDp * 0.4f, Color.WHITE, true);
        letter.setGravity(Gravity.CENTER);
        box.addView(letter, new FrameLayout.LayoutParams(-1, -1));
        box.setTag(new View[]{icon, letter});
        return box;
    }

    void bindAvatar(FrameLayout box, Entity e, String name) {
        bindAvatar(box, appIcon(e), name, typeColor(e));
    }

    /** Uses icon if available, otherwise colored background with first character of name. */
    void bindAvatar(FrameLayout box, Drawable d, String name, int color) {
        View[] v = (View[]) box.getTag();
        ImageView icon = (ImageView) v[0];
        TextView letter = (TextView) v[1];
        if (d != null) {
            icon.setImageDrawable(d);
            icon.setBackground(null);
            letter.setVisibility(View.GONE);
        } else {
            icon.setImageDrawable(null);
            icon.setBackground(round(color, dp(12)));
            letter.setText(initial(name));
            letter.setVisibility(View.VISIBLE);
        }
    }

    static String typeName(Entity e) {
        switch (e.type) {
            case Analyzer.APP: return "App";
            case Analyzer.NATIVE: return e.ksuId != null ? "Module Process" : "System Process";
            case Analyzer.KERNEL: return "Kernel Thread";
            case Analyzer.KSU: return "KSU Module";
            default: return "LSP Module";
        }
    }

    static int typeColor(Entity e) {
        switch (e.type) {
            case Analyzer.KSU: return 0xFF8B5CF6;
            case Analyzer.KERNEL: return 0xFF64748B;
            case Analyzer.NATIVE: return e.ksuId != null ? 0xFF8B5CF6 : 0xFF0EA5E9;
            case Analyzer.LSP: return 0xFFF59E0B;
            default: return 0xFF10B981;
        }
    }

    /** First letter/digit/character in name, used as avatar for iconless objects. */
    static String initial(String name) {
        if (name != null) {
            for (int i = 0; i < name.length(); ) {
                int cp = name.codePointAt(i);
                if (Character.isLetterOrDigit(cp)) return new String(Character.toChars(Character.toUpperCase(cp)));
                i += Character.charCount(cp);
            }
        }
        return "?";
    }

    /** Section title in page. */
    TextView sectionTitle(String s) {
        TextView t = text(s, 13, dim, true);
        t.setPadding(dp(4), 0, 0, 0);
        return t;
    }

    /** Icon button with rounded background (top bar refresh, floating window, etc.). */
    ImageView iconButton(int icon, String desc, View.OnClickListener l) {
        ImageView b = new ImageView(ctx);
        b.setImageResource(icon);
        b.setColorFilter(fg);
        b.setPadding(dp(10), dp(10), dp(10), dp(10));
        b.setBackground(ripple(card(dp(21))));
        b.setContentDescription(desc);
        b.setOnClickListener(l);
        return b;
    }

    static String adjName(int adj) {
        if (adj < 0) return "System Persistent";
        if (adj == 0) return "Foreground";
        if (adj < 200) return "Visible";
        if (adj < 300) return "Foreground Service";
        if (adj < 900) return "Background Service";
        return "Cached";
    }

    static String dur(long ms) {
        long s = ms / 1000;
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m " + (s % 60) + "s";
        return (s / 3600) + "h " + (s % 3600 / 60) + "m";
    }

    static String freq(long mhz) {
        if (mhz <= 0) return "--";
        return mhz >= 1000 ? String.format(Locale.US, "%.2fG", mhz / 1000.0) : mhz + "M";
    }

    /** "4 5 6" → "4-6"; separated by commas if not consecutive. */
    static String cpuRange(String cpus) {
        String[] f = cpus.trim().split("[\\s,]+");
        try {
            int a = Integer.parseInt(f[0]), b = Integer.parseInt(f[f.length - 1]);
            if (f.length == 1) return String.valueOf(a);
            if (b - a + 1 == f.length) return a + "-" + b;
        } catch (NumberFormatException ignored) {
        }
        return TextUtils.join(",", f);
    }

    /** Main temperature: SoC → Device → Battery fallback order. Returns {temp, name}. */
    static Object[] mainTemp(Analyzer.Result r) {
        if (r.maxTemp > -999) return new Object[]{r.maxTemp, "SoC Temperature"};
        if (r.skinTemp > -999) return new Object[]{r.skinTemp, "Device Temperature"};
        return new Object[]{r.batteryTemp, "Battery Temperature"};
    }

    // ------------------------------------------------------------------ Rounded Thin Progress Bar

    static final class Bar extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float frac;

        Bar(Context ctx, int trackColor) {
            super(ctx);
            track.setColor(trackColor);
        }

        void set(float f, int color) {
            frac = Math.max(0f, Math.min(1f, f));
            fill.setColor(color);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), r = h / 2f;
            rect.set(0, 0, w, h);
            cv.drawRoundRect(rect, r, r, track);
            if (frac > 0) {
                rect.set(0, 0, Math.max(h, w * frac), h);
                cv.drawRoundRect(rect, r, r, fill);
            }
        }
    }
}
