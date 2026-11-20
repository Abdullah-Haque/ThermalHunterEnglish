package com.thermalhunter.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;

import com.thermalhunter.R;
import com.thermalhunter.core.Analyzer;
import com.thermalhunter.core.Analyzer.Entity;
import com.thermalhunter.core.ModuleStatus;
import com.thermalhunter.core.Monitor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements Monitor.Listener {

    private static final String[] TABS = {"Top Heat", "KSU Modules", "LSP Modules", "Wakeup Drain"};
    private static final String[] MODE_NAMES = {"Auto", "Root Only", "Shizuku Only"};
    private static final String[] MODE_DESCS = {
            "Prioritize root; automatically fall back to Shizuku when root is unavailable",
            "Obtain superuser permissions via KernelSU etc.; most complete features",
            "Run with shell permissions via Wireless Debugging / ADB, no root required (module management & kill processes unavailable)"};

    /** Bottom navigation: Monitor / Drain / Tools / Settings. */
    static final String EXTRA_PAGE = "page";
    static final int PAGE_MONITOR = 0, PAGE_DRAIN = 1, PAGE_TOOLS = 2, PAGE_SETTINGS = 3;
    private static final String[] PAGE_NAMES = {"Monitor", "Drain", "Tools", "Settings"};
    private static final int[] PAGE_ICONS = {R.drawable.ic_monitor, R.drawable.ic_battery, R.drawable.ic_tools, R.drawable.ic_tune};

    private Monitor mon;
    private SharedPreferences prefs;
    private Ui u;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Analyzer.Result result;
    /** Segment tabs on monitor page: Heat / KSU / LSP / Wake. */
    private int tab;
    private int page = -1;
    private boolean pendingFloat;
    private String connMode = "";

    private TextView headDevice, headKernel, modeChip, banner, emptyText, loadSummary, snack;
    private TextView heroLabel, heroTemp, heroTempSub, heroPowerLabel, heroPower, heroPowerSub;
    private LinearLayout hero, clusterRow;
    private ImageView floatBtn;
    private TextView profStatus;
    private final int[] tabCounts = new int[TABS.length];
    // Only reset background on state change to avoid redrawing on every tick
    private int[] lastGrad;
    private int lastCleanState = -1;
    private final TextView[] tabViews = new TextView[TABS.length];
    private ListView list;
    // Anomaly / clean card on monitor page
    private LinearLayout cleanCard;
    private ImageView cleanIcon;
    private TextView cleanTitle, cleanSub, cleanAction;
    // Bottom navigation and pages
    private final View[] pages = new View[PAGE_NAMES.length];
    private final View[][] navItems = new View[PAGE_NAMES.length][]; // {item, pill, icon, label, dot}
    private DrainPage drainPage;
    private LinearLayout settingsBody;
    private TextView toolVpnDesc, toolCompatDesc, toolShotDesc, toolFloatDesc, toolRefreshDesc;
    private final EntityAdapter adapter = new EntityAdapter();
    private final Runnable hideSnack = () -> snack.animate().alpha(0f).translationY(20f).setDuration(200)
            .withEndAction(() -> snack.setVisibility(View.GONE)).start();

    // ------------------------------------------------------------------ Lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        u = new Ui(this);
        getWindow().setStatusBarColor(u.bg);
        getWindow().setNavigationBarColor(u.card); // Same color as bottom nav bar
        mon = Monitor.get(this);
        setContentView(buildLayout());
        int p = savedInstanceState != null ? savedInstanceState.getInt(EXTRA_PAGE, PAGE_MONITOR)
                : getIntent().getIntExtra(EXTRA_PAGE, PAGE_MONITOR);
        selectPage(p);
        syncVpnHideConfig(); // Write list to system-readable config; VPN stealth handled uniformly at system_server layer
        syncScreenshotConfig(); // Same for screenshot restriction bypass list
        DrainWatcher.schedule(this); // Background drain monitoring (based on settings switch)
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.hasExtra(EXTRA_PAGE)) selectPage(intent.getIntExtra(EXTRA_PAGE, PAGE_MONITOR));
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(EXTRA_PAGE, page);
    }

    /** Subscribe to sampling only when UI is visible; floating overlay subscribes separately when enabled. */
    @Override
    protected void onResume() {
        super.onResume();
        FloatService.stateListener = this::updateFloatBtn;
        updateFloatBtn();
        mon.addListener(this);
        if (pendingFloat) {
            pendingFloat = false;
            if (Settings.canDrawOverlays(this)) startFloat();
            else snack("Overlay permission not granted", false);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mon.removeListener(this);
        FloatService.stateListener = null;
    }

    @Override
    public void onUpdate(Analyzer.Result r) {
        render(r);
    }

    @Override
    public void onStatus(String msg, boolean error) {
        banner(msg, error ? u.bad : u.warn);
        if (error) chip("");
    }

    /** Slow down sampling (5s) when not on monitor page, keeping connection state & nav dots active. */
    @Override
    public boolean wantsFast() {
        return page == PAGE_MONITOR;
    }

    Monitor mon() {
        return mon;
    }

    /** Current connection mode: "root" / "shell" / "" (disconnected). */
    String connMode() {
        return connMode;
    }

    // ------------------------------------------------------------------ Layout

    private View buildLayout() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(u.bg);
        frame.setFitsSystemWindows(true);
        LinearLayout rootView = u.col();
        frame.addView(rootView, new FrameLayout.LayoutParams(-1, -1));

        FrameLayout pageHost = new FrameLayout(this);
        rootView.addView(pageHost, new LinearLayout.LayoutParams(-1, 0, 1));
        pages[PAGE_MONITOR] = buildMonitorPage();
        drainPage = new DrainPage(this, u);
        pages[PAGE_DRAIN] = drainPage.view;
        pages[PAGE_TOOLS] = buildToolsPage();
        pages[PAGE_SETTINGS] = buildSettingsPage();
        for (View v : pages) {
            v.setVisibility(View.GONE);
            pageHost.addView(v, new FrameLayout.LayoutParams(-1, -1));
        }
        rootView.addView(buildNav());

        // Snack hint (replaces system Toast), located above bottom nav bar
        snack = u.text("", 13, Color.WHITE, false);
        snack.setPadding(u.dp(16), u.dp(12), u.dp(16), u.dp(12));
        snack.setLineSpacing(u.dp(2), 1f);
        snack.setElevation(u.dp(6));
        snack.setVisibility(View.GONE);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        sp.setMargins(u.dp(16), 0, u.dp(16), u.dp(80));
        frame.addView(snack, sp);
        return frame;
    }

    // ------------------------------------------------------------------ Bottom Navigation

    private View buildNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setBackgroundColor(u.card);
        nav.setPadding(u.dp(8), u.dp(6), u.dp(8), u.dp(6));
        nav.setElevation(u.dp(8));
        for (int i = 0; i < PAGE_NAMES.length; i++) {
            final int idx = i;
            LinearLayout item = u.col();
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setPadding(0, u.dp(4), 0, u.dp(4));
            item.setBackground(u.ripple(Ui.round(Color.TRANSPARENT, u.dp(16))));
            FrameLayout pill = new FrameLayout(this);
            ImageView icon = new ImageView(this);
            icon.setImageResource(PAGE_ICONS[i]);
            pill.addView(icon, new FrameLayout.LayoutParams(u.dp(22), u.dp(22), Gravity.CENTER));
            View dot = new View(this);
            GradientDrawable dd = new GradientDrawable();
            dd.setShape(GradientDrawable.OVAL);
            dd.setColor(u.bad);
            dd.setStroke(u.dp(1.5f), u.card);
            dot.setBackground(dd);
            dot.setVisibility(View.GONE);
            FrameLayout.LayoutParams dp = new FrameLayout.LayoutParams(u.dp(9), u.dp(9), Gravity.TOP | Gravity.END);
            dp.setMargins(0, u.dp(3), u.dp(14), 0);
            pill.addView(dot, dp);
            item.addView(pill, new LinearLayout.LayoutParams(u.dp(60), u.dp(30)));
            TextView label = u.text(PAGE_NAMES[i], 11, u.dim, true);
            item.addView(label, Ui.lp(-2, -2, 0, u.dp(3), 0, 0));
            item.setContentDescription(PAGE_NAMES[i]);
            item.setOnClickListener(v -> selectPage(idx));
            navItems[i] = new View[]{item, pill, icon, label, dot};
            nav.addView(item, Ui.weight(1));
        }
        return nav;
    }

    private void selectPage(int p) {
        if (p < 0 || p >= pages.length) p = PAGE_MONITOR;
        if (p == page) {
            // Tap current tab again: scroll to top
            if (p == PAGE_MONITOR && list != null) list.smoothScrollToPosition(0);
            else if (pages[p] instanceof android.widget.ScrollView) ((android.widget.ScrollView) pages[p]).smoothScrollTo(0, 0);
            return;
        }
        int prev = page;
        page = p;
        for (int i = 0; i < pages.length; i++) {
            boolean sel = i == p;
            if (sel) {
                pages[i].setVisibility(View.VISIBLE);
                if (prev >= 0) {
                    pages[i].setAlpha(0f);
                    pages[i].setTranslationY(u.dp(8));
                    pages[i].animate().alpha(1f).translationY(0).setDuration(160).start();
                }
            } else {
                pages[i].setVisibility(View.GONE);
            }
            View[] n = navItems[i];
            n[1].setBackground(sel ? Ui.round(Ui.tint(u.accent), u.dp(15)) : null);
            ((ImageView) n[2]).setColorFilter(sel ? u.accent : u.dim);
            ((TextView) n[3]).setTextColor(sel ? u.accent : u.dim);
        }
        if (p == PAGE_DRAIN) drainPage.onShown();
        if (p == PAGE_SETTINGS) fillSettings(); // Rebuild each time to sync toggle states with other pages
        if (p == PAGE_TOOLS) refreshToolDescs();
        mon.kick(); // Sampling interval may change
    }

    private void navDot(int p, boolean on) {
        navItems[p][4].setVisibility(on ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onBackPressed() {
        if (page != PAGE_MONITOR) selectPage(PAGE_MONITOR);
        else super.onBackPressed();
    }

    // ------------------------------------------------------------------ Monitor Page

    private View buildMonitorPage() {
        // Header (scrolls with list)
        LinearLayout header = u.col();
        header.setPadding(u.dp(16), u.dp(14), u.dp(16), u.dp(6));

        // Top bar: title / device / mode chip / floating overlay
        LinearLayout top = u.row();
        LinearLayout titleCol = u.col();
        LinearLayout titleRow = u.row();
        titleRow.addView(u.text("ThermalHunter", 24, u.fg, true));
        modeChip = u.chip("", u.dim);
        titleRow.addView(modeChip, Ui.lp(-2, -2, u.dp(10), 0, 0, 0));
        titleCol.addView(titleRow);
        headDevice = u.text("Connecting…", 12, u.dim, false);
        headDevice.setSingleLine(true);
        headDevice.setEllipsize(TextUtils.TruncateAt.END);
        titleCol.addView(headDevice, Ui.lp(-1, -2, 0, u.dp(2), 0, 0));
        headKernel = u.text("", 11, u.dim, false);
        headKernel.setSingleLine(true);
        headKernel.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        titleCol.addView(headKernel);
        top.addView(titleCol, Ui.weight(1));
        floatBtn = u.iconButton(R.drawable.ic_float, "Floating Overlay", v -> toggleFloat());
        top.addView(floatBtn, Ui.lp(u.dp(42), u.dp(42), u.dp(8), 0, 0, 0));
        header.addView(top);
        chip("");

        // Temperature / Power card
        hero = u.row();
        hero.setPadding(u.dp(18), u.dp(16), u.dp(18), u.dp(18));
        hero.setBackground(Ui.gradient(Ui.GRAD_NONE, u.dp(24)));
        hero.setElevation(u.dp(2));
        LinearLayout hl = u.col();
        heroLabel = u.text("SoC Temperature", 12, 0xDDFFFFFF, false);
        heroTemp = u.text("--", 40, Color.WHITE, true);
        heroTemp.setFontFeatureSettings("tnum");
        heroTempSub = u.text("", 12, 0xDDFFFFFF, false);
        hl.addView(heroLabel);
        hl.addView(heroTemp);
        hl.addView(heroTempSub);
        hero.addView(hl, Ui.weight(1));
        LinearLayout hr = u.col();
        hr.setGravity(Gravity.END);
        heroPowerLabel = u.text("Discharge Power", 12, 0xDDFFFFFF, false);
        heroPower = u.text("--", 26, Color.WHITE, true);
        heroPower.setFontFeatureSettings("tnum");
        heroPowerSub = u.text("", 12, 0xDDFFFFFF, false);
        hr.addView(heroPowerLabel);
        hr.addView(heroPower);
        hr.addView(heroPowerSub);
        hero.addView(hr, Ui.lp(-2, -2, u.dp(12), 0, 0, 0));
        header.addView(hero, Ui.lp(-1, -2, 0, u.dp(14), 0, 0));

        // Load card: CPU cluster frequencies + GPU
        LinearLayout load = u.col();
        load.setPadding(u.dp(14), u.dp(12), u.dp(14), u.dp(14));
        load.setBackground(u.card(u.dp(20)));
        LinearLayout lt = u.row();
        lt.addView(u.text("Real-Time Load", 14, u.fg, true), Ui.weight(1));
        loadSummary = u.text("", 12, u.dim, false);
        loadSummary.setFontFeatureSettings("tnum");
        lt.addView(loadSummary);
        load.addView(lt);
        clusterRow = new LinearLayout(this);
        load.addView(clusterRow, Ui.lp(-1, -2, 0, u.dp(10), 0, 0));
        header.addView(load, Ui.lp(-1, -2, 0, u.dp(12), 0, 0));

        // Anomaly / One-click clean card (replaces previous large bottom button)
        cleanCard = u.row();
        cleanCard.setPadding(u.dp(14), u.dp(12), u.dp(14), u.dp(12));
        FrameLayout ib = new FrameLayout(this);
        cleanIcon = new ImageView(this);
        cleanIcon.setImageResource(R.drawable.ic_clean);
        ib.addView(cleanIcon, new FrameLayout.LayoutParams(u.dp(22), u.dp(22), Gravity.CENTER));
        cleanCard.addView(ib, Ui.lp(u.dp(40), u.dp(40), 0, 0, u.dp(12), 0));
        LinearLayout cc = u.col();
        cleanTitle = u.text("Scanning…", 15, u.fg, true);
        cleanSub = u.text("", 12, u.dim, false);
        cleanSub.setSingleLine(true);
        cleanSub.setEllipsize(TextUtils.TruncateAt.END);
        cc.addView(cleanTitle);
        cc.addView(cleanSub, Ui.lp(-1, -2, 0, u.dp(1), 0, 0));
        cleanCard.addView(cc, Ui.weight(1));
        cleanAction = u.text("Clean", 13, Color.WHITE, true);
        cleanAction.setPadding(u.dp(14), u.dp(8), u.dp(14), u.dp(8));
        cleanCard.addView(cleanAction, Ui.lp(-2, -2, u.dp(8), 0, 0, 0));
        cleanCard.setOnClickListener(v -> confirmClean());
        header.addView(cleanCard, Ui.lp(-1, -2, 0, u.dp(12), 0, 0));
        paintCleanCard(0);

        // Banner alert
        banner = u.note("", u.warn);
        banner.setTextSize(12);
        banner.setVisibility(View.GONE);
        header.addView(banner, Ui.lp(-1, -2, 0, u.dp(12), 0, 0));

        // Segmented tabs
        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(u.dp(4), u.dp(4), u.dp(4), u.dp(4));
        tabs.setBackground(u.card(u.dp(16)));
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            TextView t = u.text(TABS[i], 13, u.dim, true);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setPadding(0, u.dp(9), 0, u.dp(9));
            t.setOnClickListener(v -> {
                if (tab == idx) return;
                tab = idx;
                updateTabs();
                if (result != null) render(result);
            });
            tabViews[i] = t;
            tabs.addView(t, Ui.weight(1));
        }
        header.addView(tabs, Ui.lp(-1, -2, 0, u.dp(16), 0, u.dp(4)));
        updateTabs();

        // Auto-attribution status (LSP module page only, root mode)
        profStatus = u.note("", u.dim);
        profStatus.setTextSize(12);
        profStatus.setVisibility(View.GONE);
        header.addView(profStatus, Ui.lp(-1, -2, 0, u.dp(8), 0, u.dp(4)));

        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT)); // Cards have their own ripple
        list.setPadding(0, 0, 0, u.dp(12));
        list.setClipToPadding(false);
        list.setVerticalScrollBarEnabled(false);
        list.addHeaderView(header, null, false);
        LinearLayout footer = new LinearLayout(this);
        emptyText = u.text("Sampling…", 13, u.dim, false);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setPadding(u.dp(24), u.dp(36), u.dp(24), u.dp(36));
        footer.addView(emptyText, new LinearLayout.LayoutParams(-1, -2));
        list.addFooterView(footer, null, false);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            Object o = p.getItemAtPosition(pos);
            if (o instanceof Entity) showDetail((Entity) o);
        });
        return list;
    }

    /** Clean card color scheme: red when anomalies exist, green "Good State" otherwise. */
    private void paintCleanCard(int bad) {
        int state = bad > 0 ? 1 : 0;
        if (state == lastCleanState) return;
        lastCleanState = state;
        int c = bad > 0 ? u.bad : u.good;
        GradientDrawable bgd = Ui.round(bad > 0 ? u.badCard : u.card, u.dp(20));
        bgd.setStroke(u.dp(1), bad > 0 ? Ui.alpha(u.bad, 0x55) : u.stroke);
        cleanCard.setBackground(u.ripple(bgd));
        ((View) cleanIcon.getParent()).setBackground(Ui.round(Ui.tint(c), u.dp(14)));
        cleanIcon.setColorFilter(c);
        cleanAction.setTextColor(bad > 0 ? Color.WHITE : u.fg);
        cleanAction.setBackground(Ui.round(bad > 0 ? u.bad : u.sunken, u.dp(16)));
    }

    // ------------------------------------------------------------------ Tools Page

    private View buildToolsPage() {
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout col = u.col();
        col.setPadding(u.dp(16), u.dp(14), u.dp(16), u.dp(24));
        col.addView(u.text("Tools", 24, u.fg, true));
        col.addView(u.text("System-level fixes & utilities", 12, u.dim, false), Ui.lp(-1, -2, 0, u.dp(2), 0, 0));

        col.addView(u.sectionTitle("App Compatibility"), Ui.lp(-1, -2, 0, u.dp(20), 0, u.dp(8)));
        toolVpnDesc = u.text(vpnHideSummary(), 12, u.dim, false);
        col.addView(menuCard(R.drawable.ic_vpn, u.good, "VPN Stealth", toolVpnDesc, this::openVpnHidePicker),
                Ui.lp(-1, -2, 0, 0, 0, u.dp(10)));
        toolCompatDesc = u.text("Checking status…", 12, u.dim, false);
        col.addView(menuCard(R.drawable.ic_compat, 0xFFF59E0B, "Hardened App Compatibility Fix", toolCompatDesc, this::openCompatSheet),
                Ui.lp(-1, -2, 0, 0, 0, u.dp(10)));
        toolShotDesc = u.text(shotSummary(), 12, u.dim, false);
        col.addView(menuCard(R.drawable.ic_shot, 0xFF10B981, "Bypass Screenshot Restrictions", toolShotDesc, this::openShotPicker),
                Ui.lp(-1, -2, 0, 0, 0, 0));

        col.addView(u.sectionTitle("Display"), Ui.lp(-1, -2, 0, u.dp(20), 0, u.dp(8)));
        toolRefreshDesc = u.text(refreshSummary(), 12, u.dim, false);
        col.addView(menuCard(R.drawable.ic_refresh, 0xFF8B5CF6, "Screen Refresh Rate", toolRefreshDesc, this::openRefreshSheet),
                Ui.lp(-1, -2, 0, 0, 0, u.dp(10)));
        toolFloatDesc = u.text("", 12, u.dim, false);
        col.addView(menuCard(R.drawable.ic_float, 0xFF0EA5E9, "Floating Overlay", toolFloatDesc, this::toggleFloat),
                Ui.lp(-1, -2, 0, 0, 0, 0));

        sv.addView(col, new ViewGroup.LayoutParams(-1, -2));
        return sv;
    }

    private void refreshToolDescs() {
        toolVpnDesc.setText(vpnHideSummary());
        toolShotDesc.setText(shotSummary());
        toolRefreshDesc.setText(refreshSummary());
        updateFloatBtn();
        CompatFix.query((ok, msg, st) -> toolCompatDesc.setText(ok ? CompatFix.desc(st) : "Root required · Tap to view"));
    }

    private String refreshSummary() {
        int fps = prefs.getInt("lock_fps", 0);
        return fps > 0 ? "Locked to " + fps + "Hz · Maintained while app runs" : "Auto-adjusted by system · Can lock high refresh rate";
    }

    /** Menu card: Left colored icon + title + description + right chevron. desc passed by caller for dynamic updates. */
    private View menuCard(int icon, int tint, String title, TextView desc, Runnable onClick) {
        LinearLayout r = u.row();
        r.setPadding(u.dp(16), u.dp(15), u.dp(16), u.dp(15));
        r.setBackground(u.ripple(u.card(u.dp(18))));
        FrameLayout ib = new FrameLayout(this);
        ib.setBackground(Ui.round(Ui.tint(tint), u.dp(14)));
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setColorFilter(tint);
        ib.addView(iv, new FrameLayout.LayoutParams(u.dp(24), u.dp(24), Gravity.CENTER));
        r.addView(ib, Ui.lp(u.dp(46), u.dp(46), 0, 0, u.dp(14), 0));
        LinearLayout c = u.col();
        c.addView(u.text(title, 16, u.fg, true));
        desc.setLineSpacing(u.dp(1), 1f);
        c.addView(desc, Ui.lp(-1, -2, 0, u.dp(2), 0, 0));
        r.addView(c, Ui.weight(1));
        r.addView(u.text("›", 22, u.dim, true), Ui.lp(-2, -2, u.dp(8), 0, 0, 0));
        r.setOnClickListener(v -> onClick.run());
        return r;
    }

    private void updateTabs() {
        for (int i = 0; i < tabViews.length; i++) {
            boolean sel = i == tab;
            tabViews[i].setText(tabCounts[i] > 0 ? TABS[i] + " " + tabCounts[i] : TABS[i]);
            tabViews[i].setTextColor(sel ? Color.WHITE : u.dim);
            tabViews[i].setBackground(sel ? Ui.round(u.accent, u.dp(12)) : null);
        }
    }

    private void banner(String msg, int color) {
        banner.setText(msg);
        banner.setTextColor(color);
        banner.setBackground(Ui.round(Ui.tint(color), u.dp(14)));
        banner.setVisibility(msg == null || msg.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** Top bar mode chip: root / shell / disconnected. */
    private void chip(String mode) {
        int col = "root".equals(mode) ? u.good : "shell".equals(mode) ? u.accent : u.dim;
        modeChip.setText("root".equals(mode) ? "ROOT" : "shell".equals(mode) ? "SHIZUKU" : "DISCONNECTED");
        modeChip.setTextColor(col);
        modeChip.setBackground(Ui.round(Ui.tint(col), u.dp(10)));
    }

    void snack(String msg, boolean ok) {
        snack.setText(msg);
        snack.setBackground(Ui.round(ok ? (u.night ? 0xFF2A2F36 : 0xFF1F2328) : u.bad, u.dp(14)));
        ui.removeCallbacks(hideSnack);
        snack.animate().cancel();
        snack.setVisibility(View.VISIBLE);
        snack.setAlpha(0f);
        snack.setTranslationY(20f);
        snack.animate().alpha(1f).translationY(0f).setDuration(200).start();
        ui.postDelayed(hideSnack, 2800);
    }

    // ------------------------------------------------------------------ Rendering

    private void render(Analyzer.Result r) {
        result = r;
        JSONObject s = r.snap;
        String mode = s.optString("mode");
        connMode = mode;
        boolean shell = "shell".equals(mode);
        chip(mode);
        JSONObject dev = s.optJSONObject("device");
        if (dev != null) {
            headDevice.setText(dev.optString("model") + " · " + dev.optString("soc") + " · Android " + dev.optString("android"));
            headKernel.setText("Kernel " + dev.optString("kernel") + (shell ? "" : " · " + s.optString("rootImpl")));
        }

        // Temperature card
        Object[] mt = Ui.mainTemp(r);
        double mainTemp = (double) mt[0];
        boolean hasSoc = r.maxTemp > -999, hasSkin = r.skinTemp > -999;
        heroLabel.setText((String) mt[1]);
        heroTemp.setText(mainTemp > -999 ? Analyzer.fmt(mainTemp) + "°C" : "--");
        List<String> tsub = new ArrayList<>();
        if (hasSoc && hasSkin) tsub.add("Device " + Analyzer.fmt(r.skinTemp) + "°");
        if ((hasSoc || hasSkin) && r.batteryTemp > -999) tsub.add("Battery " + Analyzer.fmt(r.batteryTemp) + "°");
        heroTempSub.setText(TextUtils.join("  ·  ", tsub));
        double hot = Math.max(r.maxTemp, r.skinTemp);
        if (hot <= -999) hot = r.batteryTemp;
        int[] grad = Ui.heatGradient(hot);
        if (grad != lastGrad) {
            lastGrad = grad;
            hero.setBackground(Ui.gradient(grad, u.dp(24)));
        }
        JSONObject bat = s.optJSONObject("battery");
        if (bat != null) {
            long ma = Math.abs(bat.optLong("currentMa"));
            long lv = bat.optLong("level", -1);
            String lvs = lv >= 0 ? lv + "%" : "--";
            if (r.discharging) {
                heroPowerLabel.setText("Discharge Power");
                heroPower.setText(String.format(Locale.US, "%.2f W", bat.optLong("powerMw") / 1000.0));
                heroPowerSub.setText(ma + " mA · Battery " + lvs);
            } else {
                String st = bat.optString("status");
                heroPowerLabel.setText("Full".equalsIgnoreCase(st) ? "Fully Charged" : st.isEmpty() ? "Battery" : "Charging");
                heroPower.setText(ma > 0 ? ma + " mA" : "--");
                heroPowerSub.setText("Battery " + lvs);
            }
        }

        // Load card
        List<String> ls = new ArrayList<>();
        JSONObject cpu = s.optJSONObject("cpu");
        if (cpu != null) ls.add("CPU " + Analyzer.fmt(cpu.optDouble("usage")) + "%");
        JSONObject gpu = s.optJSONObject("gpu");
        if (gpu != null && (gpu.has("util") || gpu.has("mhz"))) {
            String g = "GPU";
            if (gpu.has("util")) g += " " + gpu.optLong("util") + "%";
            if (gpu.has("mhz")) g += " " + Ui.freq(gpu.optLong("mhz"));
            ls.add(g);
        }
        loadSummary.setText(TextUtils.join("  ·  ", ls));
        renderClusters(cpu == null ? null : cpu.optJSONArray("policies"));

        List<String> notes = new ArrayList<>();
        if (shell) {
            notes.add("Shizuku mode: View temperature, power, process rankings; force stop / disable apps. "
                    + "KSU / LSP modules, wakeup stats, and killing other processes require root.");
        } else if (!ModuleStatus.isActive()) {
            notes.add("LSPosed module inactive: Enable module in LSPosed with 'System Framework' scope and reboot to track app wakelocks/alarms.");
        } else if (!r.hookOk) {
            notes.add(r.hookAgeSec < 0 ? "Module active, but system framework hook not working (enable 'System Framework' scope and reboot)"
                    : "Wakeup stats expired by " + r.hookAgeSec + " s");
        }
        JSONObject lsp = s.optJSONObject("lsp");
        if (!shell && lsp != null && !lsp.optBoolean("available") && lsp.has("error")) {
            notes.add("LSPosed: " + lsp.optString("error"));
        }
        if (!shell && r.prof != null && !r.prof.optBoolean("ok") && mon.autoProfEnabled()) {
            notes.add("LSP module auto-attribution unavailable: " + r.prof.optString("error"));
        }
        if (notes.isEmpty()) banner.setVisibility(View.GONE);
        else banner(TextUtils.join("\n", notes), shell ? u.accent : u.warn);

        paintCleanCard(r.badCount);
        if (r.badCount > 0) {
            cleanTitle.setText(r.badCount == 1 ? "1 anomaly detected" : r.badCount + " anomalies detected");
            cleanSub.setText(cleanPreview(r));
        } else {
            cleanTitle.setText("No Anomalies Detected");
            cleanSub.setText("No significant heat or battery drain culprits");
        }
        navDot(PAGE_MONITOR, r.badCount > 0);
        navDot(PAGE_DRAIN, drainPage.hasBad());
        tabCounts[0] = flagged(r.heat);
        tabCounts[1] = flagged(r.ksu);
        tabCounts[2] = flagged(r.lsp);
        tabCounts[3] = flagged(r.wake);
        updateTabs();

        List<Entity> items;
        switch (tab) {
            case 1: items = r.ksu; break;
            case 2: items = r.lsp; break;
            case 3: items = r.wake; break;
            default:
                items = new ArrayList<>();
                for (Entity e : r.heat) {
                    if (items.size() >= 60) break;
                    if (e.cpuAvg >= 0.3 || e.level != Analyzer.OK) items.add(e);
                }
        }
        adapter.set(items);
        profStatus.setVisibility(tab == 2 && !shell ? View.VISIBLE : View.GONE);
        if (tab == 2 && !shell) profStatus.setText(profStatusText(r));
        emptyText.setText(emptyMessage(shell));
        emptyText.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** Subtitle of anomaly card: lists first few abnormal entity names. */
    private String cleanPreview(Analyzer.Result r) {
        List<String> names = new ArrayList<>();
        for (List<Entity> l : java.util.Arrays.asList(r.heat, r.ksu, r.lsp)) {
            for (Entity e : l) {
                if (e.level == Analyzer.BAD && names.size() < 3 && !names.contains(u.label(e))) names.add(u.label(e));
            }
        }
        return names.isEmpty() ? "Tap to view and resolve" : TextUtils.join(", ", names) + (r.badCount > names.size() ? " etc." : "");
    }

    private static int flagged(List<Entity> l) {
        int n = 0;
        for (Entity e : l) if (e.level != Analyzer.OK) n++;
        return n;
    }

    private String emptyMessage(boolean shell) {
        switch (tab) {
            case 1: return shell ? "Shizuku mode cannot read KSU modules (root required)" : "No KSU modules found";
            case 2: return shell ? "Shizuku mode cannot read LSPosed modules (root required)" : "No LSPosed modules found";
            case 3: return shell ? "Shizuku mode cannot track app wakeups (root + LSPosed required)"
                    : "No wakeup data yet (requires activating this module in LSPosed)";
            default: return "No significant heat sources, device is running cool 👍";
        }
    }

    /** CPU clusters: Current freq, freq bar, throttled hint. */
    private void renderClusters(JSONArray pol) {
        int n = pol == null ? 0 : pol.length();
        if (clusterRow.getChildCount() != n) {
            clusterRow.removeAllViews();
            for (int i = 0; i < n; i++) {
                LinearLayout t = u.col();
                t.setPadding(u.dp(10), u.dp(8), u.dp(10), u.dp(9));
                t.setBackground(Ui.round(u.sunken, u.dp(12)));
                TextView name = u.text("", 11, u.dim, false);
                name.setSingleLine(true);
                TextView val = u.text("", 15, u.fg, true);
                val.setFontFeatureSettings("tnum");
                val.setSingleLine(true);
                Ui.Bar bar = new Ui.Bar(this, u.track);
                TextView lim = u.text("", 10, u.dim, false);
                lim.setSingleLine(true);
                t.addView(name);
                t.addView(val);
                t.addView(bar, Ui.lp(-1, u.dp(4), 0, u.dp(6), 0, u.dp(5)));
                t.addView(lim);
                t.setTag(new Object[]{name, val, bar, lim});
                LinearLayout.LayoutParams lp = Ui.weight(1);
                if (i > 0) lp.leftMargin = u.dp(8);
                clusterRow.addView(t, lp);
            }
        }
        clusterRow.setVisibility(n == 0 ? View.GONE : View.VISIBLE);
        for (int i = 0; i < n; i++) {
            JSONObject o = pol.optJSONObject(i);
            if (o == null) continue;
            Object[] v = (Object[]) clusterRow.getChildAt(i).getTag();
            long cur = o.optLong("cur"), max = o.optLong("max"), limit = o.optLong("limit");
            boolean throttled = limit > 0 && max > 0 && limit < max;
            ((TextView) v[0]).setText("CPU " + Ui.cpuRange(o.optString("cpus")));
            ((TextView) v[1]).setText(Ui.freq(cur));
            ((Ui.Bar) v[2]).set(max > 0 ? cur / (float) max : 0, throttled ? u.warn : u.accent);
            TextView lim = (TextView) v[3];
            lim.setText(throttled ? "Throttled " + Ui.freq(limit) : "Max " + Ui.freq(max));
            lim.setTextColor(throttled ? u.warn : u.dim);
        }
    }

    /** Distribution by process in measured details, e.g. "system_server 3.1% · WeChat 0.4%". */
    private String measuredWhere(Entity e, int max) {
        JSONObject procs = e.measured == null ? null : e.measured.optJSONObject("procs");
        if (procs == null || procs.length() == 0) return "";
        List<String> keys = new ArrayList<>();
        for (java.util.Iterator<String> it = procs.keys(); it.hasNext(); ) keys.add(it.next());
        java.util.Collections.sort(keys, (a, b) -> Double.compare(procs.optDouble(b), procs.optDouble(a)));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < keys.size() && i < max; i++) {
            out.add(procName(keys.get(i)) + " " + Analyzer.fmt(procs.optDouble(keys.get(i))) + "%");
        }
        return TextUtils.join(" · ", out);
    }

    /** Process name to readable name: app main process uses app name. */
    private String procName(String proc) {
        String pkg = proc.contains(":") ? proc.substring(0, proc.indexOf(':')) : proc;
        Object[] info = u.appInfo(pkg);
        if (info[0] == null) return proc;
        return proc.contains(":") ? info[0] + proc.substring(proc.indexOf(':')) : (String) info[0];
    }

    /** Whether LSP module's scope has running normal apps (excluding system_server). */
    private static boolean hasAppHost(Entity e) {
        return e.scopeRunning > (e.injectsSystem && e.systemCpu >= 0 ? 1 : 0);
    }

    /** Secondary info on list card (primary value shown in large font on right). */
    private String metrics(Entity e) {
        if (tab == 3) {
            String s = "Total wakelock " + Ui.dur(e.wlHeldTotalMs) + " · Alarms " + e.alarmsTotal + " times";
            if (e.wlTag != null && !e.wlTag.isEmpty()) s += "\nLock: " + e.wlTag;
            return s;
        }
        List<String> p = new ArrayList<>();
        if (e.type == Analyzer.LSP) {
            p.add(e.sub);
            String where = measuredWhere(e, 2);
            if (!where.isEmpty()) p.add(where);
            if (!e.pids.isEmpty()) p.add("Self CPU " + Analyzer.fmt(e.cpuAvg) + "%");
            return TextUtils.join(" · ", p);
        }
        if (e.type == Analyzer.KSU && e.measuredCpu > 0.05) {
            p.add("Injected measured " + Analyzer.fmt(e.measuredCpu) + "% (" + measuredWhere(e, 2) + ")");
        }
        if (e.powerMw > 0) p.add("≈" + e.powerMw + "mW");
        if (e.rssKb > 0) p.add("RAM " + e.rssKb / 1024 + "MB");
        if (e.type == Analyzer.KSU) p.add("Proc " + e.pids.size());
        else if (e.pids.size() > 1) p.add(e.pids.size() + " processes");
        else if (e.pids.size() == 1) p.add("pid " + e.pids.get(0));
        if (e.type == Analyzer.APP && e.wlFrac > 0.05) p.add("Wakelock " + Math.round(e.wlFrac * 100) + "%");
        if (e.type == Analyzer.APP && e.minAdj <= 1000) p.add(Ui.adjName(e.minAdj));
        return TextUtils.join(" · ", p);
    }

    // ------------------------------------------------------------------ Details & Actions

    private void showDetail(Entity e) {
        Sheet s = new Sheet(this, u);
        String name = u.label(e);

        // Header: Avatar + Name + Type + Status
        LinearLayout head = u.row();
        FrameLayout av = u.avatar(52);
        u.bindAvatar(av, e, name);
        head.addView(av, Ui.lp(u.dp(52), u.dp(52), 0, 0, u.dp(14), 0));
        LinearLayout tc = u.col();
        TextView title = u.text(name, 19, u.fg, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        tc.addView(title);
        TextView sub = u.text(Ui.typeName(e) + " · " + (e.type == Analyzer.KSU ? e.sub : e.title), 12, u.dim, false);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        tc.addView(sub, Ui.lp(-1, -2, 0, u.dp(2), 0, 0));
        LinearLayout chips = u.row();
        if (e.level != Analyzer.OK) chips.addView(u.chip(e.level == Analyzer.BAD ? "Anomaly" : "Suspicious", u.levelColor(e.level)));
        if (!e.enabled) chips.addView(u.chip("Disabled", u.dim), Ui.lp(-2, -2, chips.getChildCount() > 0 ? u.dp(6) : 0, 0, 0, 0));
        if (e.isProtected) chips.addView(u.chip("Protected", u.accent), Ui.lp(-2, -2, chips.getChildCount() > 0 ? u.dp(6) : 0, 0, 0, 0));
        if (chips.getChildCount() > 0) tc.addView(chips, Ui.lp(-2, -2, 0, u.dp(6), 0, 0));
        head.addView(tc, Ui.weight(1));
        s.add(head, Ui.lp(-1, -2, 0, 0, 0, 0));

        // Data tiles
        s.add(statGrid(stats(e)), Ui.lp(-1, -2, 0, u.dp(16), 0, 0));

        if (!e.reasons.isEmpty() || !e.warnings.isEmpty()) {
            s.section("Diagnosis");
            for (String r : e.reasons) s.add(u.note(r, u.bad));
            for (String w : e.warnings) s.add(u.note(w, u.warn));
        }
        if (!e.tags.isEmpty()) {
            s.section("Tags");
            s.add(u.text(TextUtils.join(" · ", e.tags), 13, u.fg, false));
        }
        if (tab == 3 && e.wlTag != null && !e.wlTag.isEmpty()) {
            s.section("Wakelock");
            s.add(u.text(e.wlTag, 13, u.fg, false));
        }
        if (e.type == Analyzer.KSU && e.raw != null) {
            s.section("Module Info");
            String desc = e.raw.optString("desc");
            if (!desc.isEmpty()) s.add(u.text(desc, 13, u.fg, false));
            List<String> scripts = new ArrayList<>();
            if (e.raw.optBoolean("service")) scripts.add("service.sh");
            if (e.raw.optBoolean("postfs")) scripts.add("post-fs-data.sh");
            if (e.raw.optBoolean("sysprop")) scripts.add("system.prop");
            if (!scripts.isEmpty()) s.add(u.text("Scripts: " + TextUtils.join(", ", scripts), 13, u.dim, false));
        }
        if ((e.type == Analyzer.LSP || e.type == Analyzer.KSU) && e.measured != null) {
            JSONObject procs = e.measured.optJSONObject("procs");
            if (procs != null && procs.length() > 0) {
                s.section("Measured Usage Distribution (Single Core %)");
                JSONArray arr = new JSONArray();
                for (java.util.Iterator<String> it = procs.keys(); it.hasNext(); ) {
                    String k = it.next();
                    try {
                        arr.put(new JSONObject().put("name", procName(k)).put("cpu", procs.optDouble(k)));
                    } catch (Exception ignored) {
                    }
                }
                s.add(listBox(arr, 6, "cpu"));
            }
            JSONArray methods = e.measured.optJSONArray("methods");
            if (methods != null && methods.length() > 0) {
                s.section("Most Time-Consuming Module Methods (Single Core %)");
                s.add(listBox(methods, 5, "cpu"));
            }
        }
        if (e.type == Analyzer.LSP) {
            s.add(u.note(e.measuredCpu >= 0
                    ? "'Module Code Measured' comes from auto-attribution: low-frequency sampling of host processes, attributing CPU to module by call stack."
                    + " Indirect overhead from hooks (e.g. inability to inline hooked methods) is not captured, so actual impact may be slightly higher."
                    : "No measured data yet: 'Host Process CPU' above is total host usage; system_server is shared by all modules injected into the framework."
                    + " Enable auto-attribution (root) to show measured module code usage.", u.dim),
                    Ui.lp(-1, -2, 0, u.dp(12), 0, 0));
        }
        if (e.type == Analyzer.LSP && !e.scopes.isEmpty()) {
            s.section("Scope (" + e.scopes.size() + ")");
            String sc = TextUtils.join(", ", e.scopes.subList(0, Math.min(40, e.scopes.size())));
            s.add(u.text(e.scopes.size() > 40 ? sc + " …" : sc, 13, u.fg, false));
        }
        if (!e.pids.isEmpty()) {
            s.section("Processes (" + e.pids.size() + ")");
            LinearLayout box = u.col();
            box.setPadding(u.dp(12), u.dp(8), u.dp(12), u.dp(8));
            box.setBackground(Ui.round(u.sunken, u.dp(14)));
            for (int i = 0; i < e.pids.size() && i < 20; i++) {
                LinearLayout r = u.row();
                TextView pid = u.text(String.valueOf(e.pids.get(i)), 12, u.dim, false);
                pid.setTypeface(Typeface.MONOSPACE);
                r.addView(pid, Ui.lp(u.dp(56), -2, 0, 0, 0, 0));
                TextView pn = u.text(e.pidNames.get(i), 12, u.fg, false);
                pn.setSingleLine(true);
                pn.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                r.addView(pn, Ui.weight(1));
                box.addView(r, Ui.lp(-1, -2, 0, i > 0 ? u.dp(4) : 0, 0, 0));
            }
            s.add(box);
        }
        if (e.isProtected) s.add(u.note("Protected entity: To prevent system freeze or reboot, killing or disabling is not allowed.", u.accent),
                Ui.lp(-1, -2, 0, u.dp(14), 0, 0));

        // Actions
        if (!e.isProtected && !e.isSelf) {
            switch (e.type) {
                case Analyzer.APP:
                    s.action("Force Stop", Sheet.PRIMARY, () -> run("FORCESTOP " + e.pkg + " " + e.user));
                    s.action("Disable (Freeze) App", Sheet.DANGER, () -> confirm("Disable \"" + name + "\"?",
                            "After disabling, the app icon disappears and it will not start again. It can be re-enabled in this app's details.", "Disable",
                            () -> run("APP_DISABLE " + e.pkg + " " + e.user)));
                    break;
                case Analyzer.NATIVE:
                    s.action("Kill Process", Sheet.DANGER, () -> runAll(killCmds(e)));
                    break;
                case Analyzer.KSU:
                    if (!e.pids.isEmpty()) {
                        s.action("Kill All Module Processes", Sheet.PRIMARY, () -> run("KSU_KILL " + e.ksuId));
                    }
                    if (e.enabled) {
                        s.action("Disable This Module", Sheet.DANGER, () -> confirm("Disable KSU module \"" + e.title + "\"?",
                                "Processes will terminate immediately; module mounts / system properties will be reverted after reboot.", "Disable",
                                () -> run("KSU_DISABLE " + e.ksuId)));
                    } else {
                        s.action("Re-enable This Module", Sheet.PRIMARY, () -> run("KSU_ENABLE " + e.ksuId));
                    }
                    break;
                case Analyzer.LSP:
                    if (e.enabled) {
                        s.action("Disable in LSPosed", Sheet.DANGER, () -> confirm("Disable LSPosed module \"" + name + "\"?",
                                "LSPosed caches config; phone must reboot to stop injection.", "Disable",
                                () -> run("LSP_DISABLE " + e.lspPkg)));
                    } else {
                        s.action("Re-enable in LSPosed", Sheet.PRIMARY, () -> run("LSP_ENABLE " + e.lspPkg));
                    }
                    if (!e.pids.isEmpty()) {
                        s.action("Force Stop Module App Itself", Sheet.NORMAL, () -> run("FORCESTOP " + e.lspPkg + " 0"));
                    }
                    break;
            }
        }
        if (e.type == Analyzer.APP) {
            s.action("Re-enable (Unfreeze) App", Sheet.NORMAL, () -> run("APP_ENABLE " + e.pkg + " " + e.user));
        }
        s.action("Close", Sheet.NORMAL, null);
        s.show();
    }

    /** Data tiles in details page: {Title, Value, Color}. */
    private List<Object[]> stats(Entity e) {
        List<Object[]> l = new ArrayList<>();
        int lc = e.level == Analyzer.OK ? u.fg : u.levelColor(e.level);
        if (tab == 3) {
            l.add(new Object[]{"Past 2 min Wakelock", e.wlFrac >= 0 ? Math.round(e.wlFrac * 100) + "%" : "--", lc});
            l.add(new Object[]{"Wakeup Alarms", e.alarmsPerMin >= 0 ? Analyzer.fmt(e.alarmsPerMin) + "/min" : "--", u.fg});
            l.add(new Object[]{"Total Wakelock", Ui.dur(e.wlHeldTotalMs), u.fg});
            l.add(new Object[]{"Total Alarms", e.alarmsTotal + " times", u.fg});
            return l;
        }
        if (e.type == Analyzer.LSP) {
            if (e.measuredCpu >= 0) l.add(new Object[]{"Module Code Measured", Analyzer.fmt(e.measuredCpu) + "%", lc});
            l.add(new Object[]{"Host Process CPU", hasAppHost(e) ? Analyzer.fmt(e.scopeCpu) + "%" : "--", u.fg});
            l.add(new Object[]{"system_server", e.systemCpu >= 0 ? Analyzer.fmt(e.systemCpu) + "%" : "--", u.dim});
            l.add(new Object[]{"Module App Itself", e.pids.isEmpty() ? "Not running" : Analyzer.fmt(e.cpuAvg) + "%", u.fg});
            return l;
        }
        if (e.type == Analyzer.KSU && e.measuredCpu >= 0) {
            l.add(new Object[]{"Injected Code Measured", Analyzer.fmt(e.measuredCpu) + "%", lc});
            if (e.pids.isEmpty()) {
                // Modules with injected code only and no process of their own (e.g. Zygisk modules): process tiles are not meaningful
                l.add(new Object[]{"Module Own Process", "Not running", u.fg});
                return l;
            }
        }
        boolean ownCpuFlagged = e.type != Analyzer.KSU || e.cpuAvg >= 5;
        l.add(new Object[]{e.type == Analyzer.KSU ? "Module Process CPU" : "CPU (15s Avg)", Analyzer.fmt(e.cpuAvg) + "%",
                ownCpuFlagged ? lc : u.fg});
        l.add(new Object[]{"Estimated Power", e.powerMw > 0 ? e.powerMw + " mW" : "--", u.fg});
        l.add(new Object[]{"RAM", e.rssKb > 0 ? e.rssKb / 1024 + " MB" : "--", u.fg});
        l.add(new Object[]{"Processes", String.valueOf(e.pids.size()), u.fg});
        if (e.type == Analyzer.APP) {
            l.add(new Object[]{"State", e.minAdj <= 1000 ? Ui.adjName(e.minAdj) : "--", u.fg});
            l.add(new Object[]{"Held Wakelock", e.wlFrac >= 0 ? Math.round(e.wlFrac * 100) + "%" : "--", u.fg});
        }
        return l;
    }

    /** Up to 3 tiles per row. */
    View statGrid(List<Object[]> stats) {
        LinearLayout grid = u.col();
        LinearLayout row = null;
        for (int i = 0; i < stats.size(); i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                grid.addView(row, Ui.lp(-1, -2, 0, i > 0 ? u.dp(8) : 0, 0, 0));
            }
            Object[] st = stats.get(i);
            LinearLayout.LayoutParams lp = Ui.weight(1);
            if (i % 3 > 0) lp.leftMargin = u.dp(8);
            row.addView(u.tile((String) st[0], (String) st[1], (int) st[2]), lp);
        }
        // Fill remaining cells in last row to maintain consistent width
        while (row != null && row.getChildCount() < 3) {
            LinearLayout.LayoutParams lp = Ui.weight(1);
            lp.leftMargin = u.dp(8);
            row.addView(new View(this), lp);
        }
        return grid;
    }

    private static List<String> killCmds(Entity e) {
        List<String> cmds = new ArrayList<>();
        for (int i = 0; i < e.pids.size(); i++) {
            String n = e.pidNames.get(i).split(" ")[0];
            cmds.add("KILL " + e.pids.get(i) + " " + n);
        }
        return cmds;
    }

    void confirm(String title, String msg, String okLabel, Runnable ok) {
        new Sheet(this, u).title(title, msg)
                .action(okLabel, Sheet.DANGER_FILLED, ok)
                .action("Cancel", Sheet.NORMAL, null)
                .show();
    }

    void run(String cmd) {
        List<String> l = new ArrayList<>();
        l.add(cmd);
        runAll(l);
    }

    private void runAll(List<String> cmds) {
        mon.runAll(cmds, (msgs, allOk) -> {
            String joined = TextUtils.join("\n", msgs);
            // Short messages use snackbar; multiple or longer errors use bottom sheet dialog and won't auto-dismiss
            if (msgs.size() <= 2 && (allOk || (joined.length() < 60 && !joined.contains("\n")))) {
                snack(joined, allOk);
                return;
            }
            int okN = 0;
            for (String m : msgs) if (m.startsWith("✓")) okN++;
            Sheet s = new Sheet(this, u).title("Execution Result", "Success " + okN + " · Failed " + (msgs.size() - okN));
            LinearLayout box = u.col();
            box.setPadding(u.dp(12), u.dp(10), u.dp(12), u.dp(10));
            box.setBackground(Ui.round(u.sunken, u.dp(14)));
            for (int i = 0; i < msgs.size(); i++) {
                String m = msgs.get(i);
                TextView t = u.text(m, 13, m.startsWith("✓") ? u.good : u.bad, false);
                box.addView(t, Ui.lp(-1, -2, 0, i > 0 ? u.dp(6) : 0, 0, 0));
            }
            s.add(box, Ui.lp(-1, -2, 0, u.dp(14), 0, 0));
            s.action("OK", Sheet.PRIMARY, null).show();
        });
    }

    // ------------------------------------------------------------------ Profiling

    /** Status description of auto-attribution at top of LSP page. */
    private String profStatusText(Analyzer.Result r) {
        if (!mon.autoProfEnabled()) return "Auto-attribution disabled (can be enabled in Settings). Values shown below represent host process CPU, not the module's own overhead.";
        JSONObject p = r.prof;
        if (p == null) return "Auto-attribution: Preparing first sampling round (results in ~15s)…";
        if (!p.optBoolean("ok")) return "Auto-attribution unavailable; values below represent host process usage.";
        long age = Math.max(0, (r.snap.optLong("t") - p.optLong("time")) / 1000);
        String when = age < 5 ? "Just updated" : age + "s ago";
        if (p.has("idle")) return "Auto-attribution: " + p.optString("idle") + ", sampling paused · " + when;
        JSONArray t = p.optJSONArray("targets");
        List<String> names = new ArrayList<>();
        if (t != null) for (int i = 0; i < t.length(); i++) names.add(t.optString(i));
        return "Auto-attribution: Profiling " + TextUtils.join(", ", names) + " · " + when + " · Every "
                + p.optInt("period", 30) + "s";
    }

    /** {name, valueKey} list, value displayed as percentage. */
    private View listBox(JSONArray arr, int max, String valueKey) {
        LinearLayout box = u.col();
        box.setPadding(u.dp(12), u.dp(8), u.dp(12), u.dp(8));
        box.setBackground(Ui.round(u.sunken, u.dp(14)));
        for (int i = 0; i < arr.length() && i < max; i++) {
            JSONObject o = arr.optJSONObject(i);
            LinearLayout r = u.row();
            TextView n = u.text(o.optString("name"), 12, u.fg, false);
            n.setSingleLine(true);
            n.setEllipsize(TextUtils.TruncateAt.START);
            r.addView(n, Ui.weight(1));
            TextView v = u.text(Analyzer.fmt(o.optDouble(valueKey)) + "%", 12, u.dim, false);
            v.setFontFeatureSettings("tnum");
            r.addView(v, Ui.lp(-2, -2, u.dp(8), 0, 0, 0));
            box.addView(r, Ui.lp(-1, -2, 0, i > 0 ? u.dp(5) : 0, 0, 0));
        }
        return box;
    }

    // ------------------------------------------------------------------ One-Tap Clean

    private void confirmClean() {
        if (result == null) return;
        boolean includeFg = prefs.getBoolean("clean_foreground", false);
        boolean disableModules = prefs.getBoolean("clean_disable_modules", false);
        List<Entity> targets = Analyzer.cleanTargets(result, includeFg);
        boolean shell = "shell".equals(result.snap.optString("mode"));
        if (targets.isEmpty()) {
            snack("No abnormal entities detected 👍", true);
            return;
        }
        List<String> cmds = new ArrayList<>();
        LinearLayout box = u.col();
        for (Entity e : targets) {
            String act;
            int actColor;
            switch (e.type) {
                case Analyzer.APP:
                    act = "Force Stop";
                    actColor = u.accent;
                    cmds.add("FORCESTOP " + e.pkg + " " + e.user);
                    break;
                case Analyzer.NATIVE:
                    if (shell) {
                        act = "Skip";
                        actColor = u.dim;
                    } else {
                        act = "Kill Process";
                        actColor = u.bad;
                        cmds.addAll(killCmds(e));
                    }
                    break;
                case Analyzer.KSU:
                    act = disableModules ? "Disable Module" : "Kill Process";
                    actColor = u.bad;
                    cmds.add(disableModules ? "KSU_DISABLE " + e.ksuId : "KSU_KILL " + e.ksuId);
                    break;
                default:
                    act = disableModules ? "Disable Module" : "Stop Process";
                    actColor = disableModules ? u.bad : u.accent;
                    cmds.add(disableModules ? "LSP_DISABLE " + e.lspPkg : "FORCESTOP " + e.lspPkg + " 0");
            }
            String name = u.label(e);
            LinearLayout r = u.row();
            r.setPadding(u.dp(12), u.dp(10), u.dp(12), u.dp(10));
            r.setBackground(Ui.round(u.sunken, u.dp(14)));
            FrameLayout av = u.avatar(36);
            u.bindAvatar(av, e, name);
            r.addView(av, Ui.lp(u.dp(36), u.dp(36), 0, 0, u.dp(10), 0));
            LinearLayout c = u.col();
            TextView n = u.text(name, 14, u.fg, true);
            n.setSingleLine(true);
            n.setEllipsize(TextUtils.TruncateAt.END);
            c.addView(n);
            String why = e.reasons.isEmpty() ? Ui.typeName(e) : e.reasons.get(0);
            TextView w = u.text(why, 12, u.dim, false);
            w.setSingleLine(true);
            w.setEllipsize(TextUtils.TruncateAt.END);
            c.addView(w);
            r.addView(c, Ui.weight(1));
            r.addView(u.chip(act, actColor), Ui.lp(-2, -2, u.dp(8), 0, 0, 0));
            box.addView(r, Ui.lp(-1, -2, 0, box.getChildCount() > 0 ? u.dp(8) : 0, 0, 0));
        }
        String sub = shell ? "Shizuku mode lacks permission to kill system processes; these will be skipped" : "Detected abnormal entities will be handled as follows";
        Sheet s = new Sheet(this, u).title("Clean " + targets.size() + (targets.size() == 1 ? " abnormal entity" : " abnormal entities"), sub);
        s.add(box, Ui.lp(-1, -2, 0, u.dp(14), 0, 0));
        if (!cmds.isEmpty()) s.action("Clean Now", Sheet.DANGER_FILLED, () -> runAll(cmds));
        s.action("Cancel", Sheet.NORMAL, null).show();
    }

    // ------------------------------------------------------------------ Settings

    private View buildSettingsPage() {
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout col = u.col();
        col.setPadding(u.dp(16), u.dp(14), u.dp(16), u.dp(24));
        col.addView(u.text("Settings", 24, u.fg, true));
        settingsBody = u.col();
        col.addView(settingsBody, new LinearLayout.LayoutParams(-1, -2));
        sv.addView(col, new ViewGroup.LayoutParams(-1, -2));
        return sv;
    }

    private void settingsSection(String title) {
        settingsBody.addView(u.sectionTitle(title), Ui.lp(-1, -2, 0, u.dp(20), 0, u.dp(8)));
    }

    /** Rebuild settings page content (called each time entering Settings). */
    private void fillSettings() {
        LinearLayout s = settingsBody;
        s.removeAllViews();

        settingsSection("Working Mode");
        String conn = "root".equals(connMode) ? "Currently connected via root" : "shell".equals(connMode) ? "Currently connected via Shizuku" : "Currently disconnected";
        List<View[]> opts = new ArrayList<>();
        for (int i = 0; i < Monitor.MODES.length; i++) {
            final String mode = Monitor.MODES[i];
            LinearLayout r = u.row();
            r.setPadding(u.dp(14), u.dp(12), u.dp(14), u.dp(12));
            View ind = new View(this);
            r.addView(ind, Ui.lp(u.dp(20), u.dp(20), 0, 0, u.dp(12), 0));
            LinearLayout c = u.col();
            c.addView(u.text(MODE_NAMES[i], 15, u.fg, true));
            TextView d = u.text(MODE_DESCS[i], 12, u.dim, false);
            d.setLineSpacing(u.dp(1), 1f);
            c.addView(d, Ui.lp(-1, -2, 0, u.dp(2), 0, 0));
            r.addView(c, Ui.weight(1));
            opts.add(new View[]{r, ind});
            s.addView(r, Ui.lp(-1, -2, 0, i > 0 ? u.dp(8) : 0, 0, 0));
            r.setTag(mode);
        }
        Runnable paint = () -> {
            String m = prefs.getString("work_mode", "auto");
            for (View[] o : opts) {
                boolean sel = m.equals(o[0].getTag());
                GradientDrawable g = sel ? Ui.round(Ui.tint(u.accent), u.dp(16)) : u.card(u.dp(16));
                if (sel) g.setStroke(u.dp(1), Ui.alpha(u.accent, 0x99));
                o[0].setBackground(u.ripple(g));
                o[1].setBackground(radio(sel));
            }
        };
        for (View[] o : opts) {
            o[0].setOnClickListener(v -> {
                String m = (String) v.getTag();
                if (m.equals(prefs.getString("work_mode", "auto"))) return;
                prefs.edit().putString("work_mode", m).apply();
                paint.run();
                mon.reconnect(); // Close current session and reconnect under new mode
                snack("Switched to \"" + MODE_NAMES[java.util.Arrays.asList(Monitor.MODES).indexOf(m)] + "\", reconnecting...", true);
            });
        }
        paint.run();
        s.addView(u.text(conn, 12, u.dim, false), Ui.lp(-1, -2, u.dp(4), u.dp(8), 0, 0));

        settingsSection("Battery Stats");
        s.addView(switchRow("Background battery drain monitoring",
                "Read system battery stats approx. once per hour (does not wake device, not persistent in background); send notification when severe anomalies like long background wakelock, frequent alarms, or rapid standby drain occur (root required)",
                DrainWatcher.enabled(this), on -> DrainWatcher.setEnabled(this, on)));

        settingsSection("LSP Modules");
        s.addView(switchRow("Auto-attribute module CPU (root)",
                "Use system built-in simpleperf to sample injected processes every 30s at low frequency, calculating CPU used by each module from call stacks; uses minimal extra CPU, runs only when UI or overlay is active",
                mon.autoProfEnabled(), on -> mon.setAutoProf(on)));

        settingsSection("One-Tap Clean");
        s.addView(switchRow("Include foreground apps", "By default only background abnormal apps are handled",
                prefs.getBoolean("clean_foreground", false),
                on -> prefs.edit().putBoolean("clean_foreground", on).apply()));
        s.addView(switchRow("Directly disable abnormal KSU / LSP modules", "When disabled, only module processes are killed; disabling requires a phone reboot to take full effect",
                prefs.getBoolean("clean_disable_modules", false),
                on -> prefs.edit().putBoolean("clean_disable_modules", on).apply()), Ui.lp(-1, -2, 0, u.dp(8), 0, 0));

        settingsSection("Detection Rules");
        s.addView(u.note("Real-Time (Single-core CPU %, ~15s average)\n"
                + "· Any app ≥35%; Background ≥10%; Cached process ≥3%\n"
                + "· KSU module processes combined ≥5%; other native processes ≥20%\n"
                + "· Background held wakelock ≥50% of time, or wakeup alarms ≥6 times/min\n"
                + "· LSP module: Self background ≥8%; flagged suspicious when abnormal apps exist in scope\n\n"
                + "Long-Term (Since last full charge, evaluated after discharging ≥30 min)\n"
                + "· Background wakelock ≥20 min and ≥15% of discharge time\n"
                + "· Wakeup alarms ≥30 times/hour; Background location ≥2 hours\n"
                + "· CPU time exceeds foreground duration by over 90 minutes\n"
                + "· Foreground <15 min yet accounts for ≥8% of total app battery drain\n"
                + "· Screen-off standby ≥3%/hour; Screen-off awake ratio ≥40%", u.dim));

        settingsSection("System");
        TextView reboot = u.text("Reboot Phone", 15, u.bad, true);
        reboot.setGravity(Gravity.CENTER);
        reboot.setPadding(0, u.dp(13), 0, u.dp(13));
        reboot.setBackground(u.ripple(Ui.round(Ui.tint(u.bad), u.dp(16))));
        reboot.setOnClickListener(v -> confirm("Reboot phone now?",
                "Module disabling requires a reboot to take full effect.", "Reboot", () -> run("REBOOT")));
        s.addView(reboot);

        String ver = "";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        TextView about = u.text("ThermalHunter " + ver + "\nAll data is processed on-device, no network access", 12, u.dim, false);
        about.setGravity(Gravity.CENTER);
        about.setLineSpacing(u.dp(2), 1f);
        s.addView(about, Ui.lp(-1, -2, 0, u.dp(24), 0, 0));
    }

    /** Radio indicator: filled dot + outer ring when selected. */
    private android.graphics.drawable.Drawable radio(boolean sel) {
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setStroke(u.dp(2), sel ? u.accent : u.dim);
        if (!sel) return ring;
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(u.accent);
        LayerDrawable ld = new LayerDrawable(new android.graphics.drawable.Drawable[]{ring, dot});
        int in = u.dp(5);
        ld.setLayerInset(1, in, in, in, in);
        return ld;
    }

    interface OnToggle {
        void onToggle(boolean on);
    }

    View switchRow(String title, String desc, boolean checked, OnToggle l) {
        LinearLayout r = u.row();
        r.setPadding(u.dp(14), u.dp(12), u.dp(10), u.dp(12));
        r.setBackground(u.ripple(u.card(u.dp(16))));
        LinearLayout c = u.col();
        c.addView(u.text(title, 15, u.fg, true));
        TextView d = u.text(desc, 12, u.dim, false);
        d.setLineSpacing(u.dp(1), 1f);
        c.addView(d, Ui.lp(-1, -2, 0, u.dp(2), 0, 0));
        r.addView(c, Ui.weight(1));
        Switch sw = new Switch(this);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener((b, on) -> l.onToggle(on));
        r.addView(sw, Ui.lp(-2, -2, u.dp(8), 0, 0, 0));
        r.setOnClickListener(v -> sw.toggle());
        return r;
    }

    // ------------------------------------------------------------------ Refresh Rate

    private void openRefreshSheet() {
        snack("Reading refresh rates…", true);
        mon.call("REFRESH_INFO", r -> {
            ui.removeCallbacks(hideSnack);
            snack.setVisibility(View.GONE);
            JSONArray rates = r.optJSONArray("rates");
            if (!r.optBoolean("ok") || rates == null || rates.length() == 0) {
                snack("Could not read supported refresh rates" + (r.has("error") ? ": " + r.optString("error") : ""), false);
                return;
            }
            int max = rates.optInt(rates.length() - 1);
            StringBuilder rs = new StringBuilder();
            for (int i = 0; i < rates.length(); i++) rs.append(i > 0 ? "/" : "").append(rates.optInt(i));
            Sheet s = new Sheet(this, u).title("Screen Refresh Rate",
                    "Supported " + rs + "Hz · Current " + r.optInt("current", -1) + "Hz. Locking ignores system per-app throttling; may need re-setting after screen turns off.");
            for (int i = rates.length() - 1; i >= 0; i--) {
                int fps = rates.optInt(i);
                s.action("Lock " + fps + "Hz" + (fps == max ? " (Highest)" : ""),
                        fps == max ? Sheet.PRIMARY : Sheet.NORMAL, () -> applyRefresh(fps));
            }
            s.action("System Controlled (Auto)", Sheet.NORMAL, () -> applyRefresh(0));
            s.action("Cancel", Sheet.NORMAL, null);
            s.show();
        });
    }

    private void applyRefresh(int fps) {
        prefs.edit().putInt("lock_fps", fps).apply(); // Remember choice, maintained automatically while App runs
        toolRefreshDesc.setText(refreshSummary());
        mon.call("REFRESH_SET " + fps, r ->
                snack(r.optBoolean("ok") ? r.optString("msg") : (r.has("error") ? r.optString("error") : "Failed to set"),
                        r.optBoolean("ok")));
    }

    // ------------------------------------------------------------------ Hardened App Compatibility Fix

    private void openCompatSheet() {
        snack("Checking fix status…", true);
        CompatFix.query((ok, msg, st) -> {
            snack.setVisibility(View.GONE);
            if (!ok || st == null) {
                snack(msg, false);
                toolCompatDesc.setText("Root required · Tap to view");
                return;
            }
            Sheet s = new Sheet(this, u).title("Hardened App Compatibility Fix", st.active
                    ? "Current: Enabled (Shadow mount active, hardened apps read enforce=0)"
                    : "Current: Disabled");
            s.add(u.note(
                    "Some hardened apps (e.g. digital RMB) crash immediately upon launch on rooted devices: "
                            + "hardened self-checks deliberately crash when reading SELinux as Enforcing and detecting root.\n\n"
                            + "This fix bind-mounts 0 over /sys/fs/selinux/enforce, "
                            + "allowing hardened apps to pass as non-Enforcing.\n\n"
                            + "· Actual SELinux state and permissions remain completely unchanged\n"
                            + "· Takes effect immediately, no reboot required\n"
                            + "· Needs to be re-enabled after reboot / soft reboot\n"
                            + "· While mounted, tools like getenforce will display Permissive (only reading shadow)\n"
                            + "· Disabling umounts immediately, cleanly restoring in one step", u.dim));
            if (st.active) {
                s.action("Disable (Restore Actual State)", Sheet.DANGER, () -> runCompat(false));
                s.action("Done", Sheet.NORMAL, null);
            } else {
                s.action("Enable Fix", Sheet.PRIMARY, () -> runCompat(true));
                s.action("Cancel", Sheet.NORMAL, null);
            }
            s.show();
        });
    }

    private void runCompat(boolean enable) {
        snack(enable ? "Enabling…" : "Restoring…", true);
        CompatFix.set(enable, (ok, msg, st) -> {
            snack(msg, ok);
            if (st != null) toolCompatDesc.setText(CompatFix.desc(st));
            else toolCompatDesc.setText(ok ? CompatFix.desc(null) : "Root required · Tap to view");
        });
    }

    // ------------------------------------------------------------------ VPN Stealth

    /** Local list (source of truth for UI). Old MODE_WORLD_READABLE redirection unreliable on targetSdk 35, deprecated. */
    private SharedPreferences hidePrefs() {
        return getSharedPreferences("hide", MODE_PRIVATE);
    }

    /**
     * Write list to /data/system/thermalhunter/vpnhide.json (owned by system, readable by system_server),
     * the sole source for VPN stealth: system_server strips VPN flag for target apps per this list, no need to configure LSPosed scope per app.
     * Old "private prefs changed to world readable" does not work on this device (0660 + SELinux isolation), so write to system dir via root.
     */
    private void syncVpnHideConfig() {
        final java.util.Set<String> pkgs = vpnHidePkgs();
        new Thread(() -> {
            try {
                org.json.JSONArray arr = new org.json.JSONArray();
                for (String p : pkgs) arr.put(p);
                String b64 = android.util.Base64.encodeToString(
                        arr.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        android.util.Base64.NO_WRAP);
                // Directory must be owned by system: Stats.flush writes stats.json with uid 1000,
                // if this process (root) creates directory on fresh install without chown, system cannot write → attribution stats silently fail.
                String cmd = "mkdir -p /data/system/thermalhunter; "
                        + "chown system:system /data/system/thermalhunter; "
                        + "chmod 700 /data/system/thermalhunter; echo '" + b64
                        + "' | base64 -d > /data/system/thermalhunter/vpnhide.json; "
                        + "chmod 644 /data/system/thermalhunter/vpnhide.json";
                Process pr = new ProcessBuilder("su", "-c", cmd).start();
                if (!pr.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) pr.destroyForcibly();
            } catch (Throwable ignored) {
            }
        }, "vpnhide-sync").start();
    }

    private java.util.Set<String> vpnHidePkgs() {
        return new java.util.HashSet<>(hidePrefs().getStringSet("vpn_pkgs", java.util.Collections.emptySet()));
    }

    private String vpnHideSummary() {
        int n = vpnHidePkgs().size();
        return n == 0 ? "No apps selected yet" : "Enabled for " + n + (n == 1 ? " app" : " apps");
    }

    private void openVpnHidePicker() {
        snack("Reading app list…", true);
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> apps = new ArrayList<>();
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                // Show third-party apps + updated system apps with icons; exclude self
                if ("com.thermalhunter".equals(ai.packageName)) continue;
                if (system && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) continue;
                apps.add(ai);
            }
            apps.sort((a, b) -> pm.getApplicationLabel(a).toString()
                    .compareToIgnoreCase(pm.getApplicationLabel(b).toString()));
            java.util.Set<String> selected = vpnHidePkgs();
            ui.post(() -> showVpnHidePicker(apps, selected));
        }, "app-list").start();
    }

    private void showVpnHidePicker(List<ApplicationInfo> apps, java.util.Set<String> selected) {
        ui.removeCallbacks(hideSnack);
        snack.setVisibility(View.GONE);
        PackageManager pm = getPackageManager();
        java.util.Set<String> working = new java.util.HashSet<>(selected);
        Sheet s = new Sheet(this, u).title("VPN Stealth", "Select once here; no need to configure scope in LSPosed");

        // Pinned top search box
        EditText search = new EditText(this);
        search.setHint("Search app name or package");
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setTextColor(u.fg);
        search.setHintTextColor(u.dim);
        search.setBackground(Ui.round(u.sunken, u.dp(12)));
        search.setPadding(u.dp(14), u.dp(11), u.dp(14), u.dp(11));

        LinearLayout list = u.col();
        final List<View> rows = new ArrayList<>();
        final List<String> keys = new ArrayList<>();
        for (ApplicationInfo ai : apps) {
            String pkg = ai.packageName;
            String label = pm.getApplicationLabel(ai).toString();
            LinearLayout r = u.row();
            r.setPadding(u.dp(12), u.dp(10), u.dp(12), u.dp(10));
            r.setBackground(Ui.round(u.sunken, u.dp(14)));
            ImageView icon = new ImageView(this);
            try {
                icon.setImageDrawable(pm.getApplicationIcon(ai));
            } catch (Throwable ignored) {
            }
            r.addView(icon, Ui.lp(u.dp(34), u.dp(34), 0, 0, u.dp(10), 0));
            LinearLayout c = u.col();
            TextView t = u.text(label, 14, u.fg, true);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            c.addView(t);
            TextView pt = u.text(pkg, 11, u.dim, false);
            pt.setSingleLine(true);
            pt.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            c.addView(pt);
            r.addView(c, Ui.weight(1));
            Switch sw = new Switch(this);
            sw.setChecked(working.contains(pkg));
            sw.setOnCheckedChangeListener((b, on) -> {
                if (on) working.add(pkg);
                else working.remove(pkg);
            });
            r.addView(sw, Ui.lp(-2, -2, u.dp(8), 0, 0, 0));
            r.setOnClickListener(v -> sw.toggle());
            list.addView(r, Ui.lp(-1, -2, 0, rows.isEmpty() ? 0 : u.dp(8), 0, 0));
            rows.add(r);
            keys.add((label + " " + pkg).toLowerCase(Locale.ROOT));
        }
        TextView empty = u.text("No matching apps", 13, u.dim, false);
        empty.setPadding(0, u.dp(16), 0, 0);
        empty.setVisibility(View.GONE);
        list.addView(empty);

        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence x, int a, int b, int c) {}
            public void onTextChanged(CharSequence x, int a, int b, int c) {}
            public void afterTextChanged(Editable e) {
                String q = e.toString().trim().toLowerCase(Locale.ROOT);
                int shown = 0;
                for (int i = 0; i < rows.size(); i++) {
                    boolean match = q.isEmpty() || keys.get(i).contains(q);
                    rows.get(i).setVisibility(match ? View.VISIBLE : View.GONE);
                    if (match) shown++;
                }
                empty.setVisibility(shown == 0 ? View.VISIBLE : View.GONE);
            }
        });

        s.pin(search);
        s.add(list);
        s.add(u.note("Stealth is handled uniformly at the system framework layer: takes effect after toggling and restarting the app."
                + " One-time prerequisite: Module enabled in LSPosed with 'System Framework' in scope."
                + " Limitation: Does not cover apps directly reading tun0 interface or native-level checks.", u.dim),
                Ui.lp(-1, -2, 0, u.dp(12), 0, 0));
        s.action("Save", Sheet.PRIMARY, () -> {
            hidePrefs().edit().putStringSet("vpn_pkgs", working).commit();
            syncVpnHideConfig();
            toolVpnDesc.setText(vpnHideSummary());
            snack(working.isEmpty() ? "VPN stealth list cleared"
                    : "Enabled for " + working.size() + " apps, takes effect after restarting the apps", true);
        });
        s.action("Cancel", Sheet.NORMAL, null);
        s.show();
    }

    // ------------------------------------------------------------------ Bypass Screenshot Restrictions

    /** Local storage for screenshot unlock list (UI source of truth). */
    private SharedPreferences shotPrefs() {
        return getSharedPreferences("screenshot", MODE_PRIVATE);
    }

    /** Writes list to /data/system/thermalhunter/screenshot.json; system_server clears FLAG_SECURE per list. */
    private void syncScreenshotConfig() {
        final java.util.Set<String> pkgs = shotPkgs();
        new Thread(() -> {
            try {
                org.json.JSONArray arr = new org.json.JSONArray();
                for (String p : pkgs) arr.put(p);
                String b64 = android.util.Base64.encodeToString(
                        arr.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        android.util.Base64.NO_WRAP);
                String cmd = "mkdir -p /data/system/thermalhunter; "
                        + "chown system:system /data/system/thermalhunter; "
                        + "chmod 700 /data/system/thermalhunter; echo '" + b64
                        + "' | base64 -d > /data/system/thermalhunter/screenshot.json; "
                        + "chmod 644 /data/system/thermalhunter/screenshot.json";
                Process pr = new ProcessBuilder("su", "-c", cmd).start();
                if (!pr.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) pr.destroyForcibly();
            } catch (Throwable ignored) {
            }
        }, "shot-sync").start();
    }

    private java.util.Set<String> shotPkgs() {
        return new java.util.HashSet<>(shotPrefs().getStringSet("pkgs", java.util.Collections.emptySet()));
    }

    private String shotSummary() {
        int n = shotPkgs().size();
        return n == 0 ? "No apps selected yet" : "Bypassed for " + n + (n == 1 ? " app" : " apps");
    }

    private void openShotPicker() {
        snack("Reading app list…", true);
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> apps = new ArrayList<>();
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                if ("com.thermalhunter".equals(ai.packageName)) continue;
                if (system && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) continue;
                apps.add(ai);
            }
            apps.sort((a, b) -> pm.getApplicationLabel(a).toString()
                    .compareToIgnoreCase(pm.getApplicationLabel(b).toString()));
            java.util.Set<String> selected = shotPkgs();
            ui.post(() -> showShotPicker(apps, selected));
        }, "app-list-shot").start();
    }

    private void showShotPicker(List<ApplicationInfo> apps, java.util.Set<String> selected) {
        ui.removeCallbacks(hideSnack);
        snack.setVisibility(View.GONE);
        PackageManager pm = getPackageManager();
        java.util.Set<String> working = new java.util.HashSet<>(selected);
        Sheet s = new Sheet(this, u).title("Bypass Screenshot Restrictions",
                "Select apps to allow screenshot / screen recording (mostly FLAG_SECURE set by banking and payment apps)");

        EditText search = new EditText(this);
        search.setHint("Search app name or package");
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setTextColor(u.fg);
        search.setHintTextColor(u.dim);
        search.setBackground(Ui.round(u.sunken, u.dp(12)));
        search.setPadding(u.dp(14), u.dp(11), u.dp(14), u.dp(11));

        LinearLayout list = u.col();
        final List<View> rows = new ArrayList<>();
        final List<String> keys = new ArrayList<>();
        for (ApplicationInfo ai : apps) {
            String pkg = ai.packageName;
            String label = pm.getApplicationLabel(ai).toString();
            LinearLayout r = u.row();
            r.setPadding(u.dp(12), u.dp(10), u.dp(12), u.dp(10));
            r.setBackground(Ui.round(u.sunken, u.dp(14)));
            ImageView icon = new ImageView(this);
            try {
                icon.setImageDrawable(pm.getApplicationIcon(ai));
            } catch (Throwable ignored) {
            }
            r.addView(icon, Ui.lp(u.dp(34), u.dp(34), 0, 0, u.dp(10), 0));
            LinearLayout c = u.col();
            TextView t = u.text(label, 14, u.fg, true);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            c.addView(t);
            TextView pt = u.text(pkg, 11, u.dim, false);
            pt.setSingleLine(true);
            pt.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            c.addView(pt);
            r.addView(c, Ui.weight(1));
            Switch sw = new Switch(this);
            sw.setChecked(working.contains(pkg));
            sw.setOnCheckedChangeListener((b, on) -> {
                if (on) working.add(pkg);
                else working.remove(pkg);
            });
            r.addView(sw, Ui.lp(-2, -2, u.dp(8), 0, 0, 0));
            r.setOnClickListener(v -> sw.toggle());
            list.addView(r, Ui.lp(-1, -2, 0, rows.isEmpty() ? 0 : u.dp(8), 0, 0));
            rows.add(r);
            keys.add((label + " " + pkg).toLowerCase(Locale.ROOT));
        }
        TextView empty = u.text("No matching apps", 13, u.dim, false);
        empty.setPadding(0, u.dp(16), 0, 0);
        empty.setVisibility(View.GONE);
        list.addView(empty);

        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence x, int a, int b, int c) {}
            public void onTextChanged(CharSequence x, int a, int b, int c) {}
            public void afterTextChanged(Editable e) {
                String q = e.toString().trim().toLowerCase(Locale.ROOT);
                int shown = 0;
                for (int i = 0; i < rows.size(); i++) {
                    boolean match = q.isEmpty() || keys.get(i).contains(q);
                    rows.get(i).setVisibility(match ? View.VISIBLE : View.GONE);
                    if (match) shown++;
                }
                empty.setVisibility(shown == 0 ? View.VISIBLE : View.GONE);
            }
        });

        s.pin(search);
        s.add(list);
        s.add(u.note("Bypass is handled uniformly at the system framework layer: restart the app after selecting to enable screenshots / screen recording."
                + " One-time prerequisite: Module enabled in LSPosed with 'System Framework' in scope."
                + " Note: Bypass only affects selected apps; other apps' secure flags remain unaffected.", u.dim),
                Ui.lp(-1, -2, 0, u.dp(12), 0, 0));
        s.action("Save", Sheet.PRIMARY, () -> {
            shotPrefs().edit().putStringSet("pkgs", working).commit();
            syncScreenshotConfig();
            toolShotDesc.setText(shotSummary());
            snack(working.isEmpty() ? "Screenshot bypass list cleared"
                    : "Bypassed for " + working.size() + " apps, takes effect after restarting the apps", true);
        });
        s.action("Cancel", Sheet.NORMAL, null);
        s.show();
    }

    // ------------------------------------------------------------------ Floating Overlay

    private void toggleFloat() {
        if (FloatService.isRunning()) {
            FloatService.stop(this);
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            pendingFloat = true;
            snack("Please grant ThermalHunter \"Display over other apps\" permission", true);
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Throwable t) {
                pendingFloat = false;
                snack("Unable to open overlay permission settings; please enable manually in system settings", false);
            }
            return;
        }
        startFloat();
    }

    private void startFloat() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        FloatService.start(this);
    }

    private void updateFloatBtn() {
        boolean on = FloatService.isRunning();
        floatBtn.setColorFilter(on ? Color.WHITE : u.fg);
        floatBtn.setBackground(u.ripple(on ? Ui.round(u.accent, u.dp(21)) : u.card(u.dp(21))));
        if (toolFloatDesc != null) toolFloatDesc.setText(on ? "Enabled · Tap to close" : "Display temperature and heat sources over any app · Tap to open");
    }

    // ------------------------------------------------------------------ List

    private final class EntityAdapter extends BaseAdapter {
        final List<Entity> items = new ArrayList<>();

        void set(List<Entity> l) {
            items.clear();
            items.addAll(l);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int i) {
            return items.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            Holder h;
            if (convert == null) {
                h = new Holder();
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setPadding(u.dp(14), u.dp(12), u.dp(14), u.dp(12));
                row.setDuplicateParentStateEnabled(true); // Let card ripple respond to list item click

                h.avatar = u.avatar(42);
                row.addView(h.avatar, Ui.lp(u.dp(42), u.dp(42), 0, 0, u.dp(12), 0));

                LinearLayout colv = u.col();
                LinearLayout top = u.row();
                h.title = u.text("", 15, u.fg, true);
                h.title.setSingleLine(true);
                h.title.setEllipsize(TextUtils.TruncateAt.END);
                top.addView(h.title, Ui.weight(1));
                h.badge = u.chip("", u.bad);
                h.badge.setTextSize(10);
                top.addView(h.badge, Ui.lp(-2, -2, u.dp(6), 0, 0, 0));
                colv.addView(top);
                h.sub = u.text("", 11, u.dim, false);
                h.sub.setSingleLine(true);
                h.sub.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                colv.addView(h.sub, Ui.lp(-1, -2, 0, u.dp(1), 0, 0));
                h.metrics = u.text("", 12, u.dim, false);
                colv.addView(h.metrics, Ui.lp(-1, -2, 0, u.dp(4), 0, 0));
                h.bar = new Ui.Bar(MainActivity.this, u.track);
                colv.addView(h.bar, Ui.lp(-1, u.dp(3), 0, u.dp(8), 0, 0));
                h.reasons = u.text("", 12, u.bad, false);
                h.reasons.setLineSpacing(u.dp(1), 1f);
                colv.addView(h.reasons, Ui.lp(-1, -2, 0, u.dp(6), 0, 0));
                row.addView(colv, Ui.weight(1));

                LinearLayout valCol = u.col();
                valCol.setGravity(Gravity.END);
                h.value = u.text("", 17, u.fg, true);
                h.value.setFontFeatureSettings("tnum");
                h.caption = u.text("", 10, u.dim, false);
                valCol.addView(h.value);
                valCol.addView(h.caption);
                row.addView(valCol, Ui.lp(-2, -2, u.dp(10), 0, 0, 0));

                LinearLayout wrap = new LinearLayout(MainActivity.this);
                wrap.setPadding(u.dp(16), u.dp(5), u.dp(16), u.dp(5));
                wrap.addView(row, new LinearLayout.LayoutParams(-1, -2));
                h.card = row;
                wrap.setTag(h);
                convert = wrap;
            } else {
                h = (Holder) convert.getTag();
            }
            Entity e = items.get(pos);
            String name = u.label(e);
            h.title.setText(name);
            String sub = Ui.typeName(e) + " · " + (e.type == Analyzer.KSU ? e.sub : e.title);
            if (!e.enabled) sub = "Disabled · " + sub;
            h.sub.setText(sub);
            String m = metrics(e);
            h.metrics.setText(m);
            h.metrics.setVisibility(m.isEmpty() ? View.GONE : View.VISIBLE);

            // Right primary value + usage bar
            double frac;
            if (tab == 3) {
                h.value.setText(e.wlFrac >= 0 ? Math.round(e.wlFrac * 100) + "%" : "--");
                h.caption.setText("Wakelock");
                frac = Math.max(0, e.wlFrac);
            } else if (e.type == Analyzer.KSU && e.measuredCpu > 0.05) {
                double v = e.cpuAvg + e.measuredCpu;
                h.value.setText(Analyzer.fmt(v) + "%");
                h.caption.setText("CPU (inc. inj)");
                frac = v / 100;
            } else if (e.type == Analyzer.LSP && e.measuredCpu >= 0) {
                h.value.setText(Analyzer.fmt(e.measuredCpu) + "%");
                h.caption.setText("Measured CPU");
                frac = e.measuredCpu / 100;
            } else if (e.type == Analyzer.LSP) {
                // No measured data: show host process usage and note it
                if (hasAppHost(e)) {
                    h.value.setText(Analyzer.fmt(e.scopeCpu) + "%");
                    h.caption.setText("Host Process CPU");
                    frac = e.scopeCpu / 100;
                } else if (e.injectsSystem && e.systemCpu >= 0) {
                    h.value.setText(Analyzer.fmt(e.systemCpu) + "%");
                    h.caption.setText("system_server (shared)");
                    frac = 0;
                } else {
                    h.value.setText("--");
                    h.caption.setText("Host not running");
                    frac = 0;
                }
            } else {
                h.value.setText(Analyzer.fmt(e.cpuAvg) + "%");
                h.caption.setText("CPU");
                frac = e.cpuAvg / 100;
            }
            int lc = u.levelColor(e.level);
            boolean sharedOnly = e.type == Analyzer.LSP && e.measuredCpu < 0 && !hasAppHost(e);
            h.value.setTextColor(sharedOnly ? u.dim : e.level == Analyzer.OK ? u.fg : lc);
            h.bar.set((float) Math.min(1, frac), lc);

            StringBuilder rs = new StringBuilder();
            for (String r : e.reasons) rs.append(rs.length() > 0 ? "\n" : "").append("❗ ").append(r);
            for (String w : e.warnings) rs.append(rs.length() > 0 ? "\n" : "").append("⚠ ").append(w);
            List<String> tags = new ArrayList<>(e.tags);
            if (e.isProtected) tags.add("Protected");
            if (!tags.isEmpty()) rs.append(rs.length() > 0 ? "\n" : "").append("🏷 ").append(TextUtils.join(" · ", tags));
            h.reasons.setText(rs);
            h.reasons.setTextColor(e.level == Analyzer.BAD ? u.bad : e.level == Analyzer.WARN ? u.warn : u.dim);
            h.reasons.setVisibility(rs.length() == 0 ? View.GONE : View.VISIBLE);
            if (e.level != Analyzer.OK) {
                int bc = u.levelColor(e.level);
                h.badge.setText(e.level == Analyzer.BAD ? "Anomaly" : "Suspicious");
                h.badge.setTextColor(bc);
                h.badge.setBackground(Ui.round(Ui.tint(bc), u.dp(10)));
                h.badge.setVisibility(View.VISIBLE);
            } else {
                h.badge.setVisibility(View.GONE);
            }
            u.bindAvatar(h.avatar, e, name);

            boolean badCard = e.level == Analyzer.BAD;
            if (h.badBg == null || h.badBg != badCard) {
                h.badBg = badCard;
                GradientDrawable bgd = Ui.round(badCard ? u.badCard : u.card, u.dp(18));
                bgd.setStroke(u.dp(1), badCard ? Ui.alpha(u.bad, 0x55) : u.stroke);
                h.card.setBackground(u.ripple(bgd));
            }
            h.card.setAlpha(e.enabled ? 1f : 0.55f);
            return convert;
        }
    }

    private static final class Holder {
        LinearLayout card;
        FrameLayout avatar;
        Ui.Bar bar;
        TextView title, sub, metrics, reasons, badge, value, caption;
        Boolean badBg;
    }
}
