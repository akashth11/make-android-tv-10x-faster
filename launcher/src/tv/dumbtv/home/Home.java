package tv.dumbtv.home;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.format.DateFormat;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class Home extends Activity {
    /** How long Menu is held to open Options while the child lock is on. */
    static final long UNLOCK_MS = 3000;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService colorIo = Executors.newSingleThreadExecutor();
    private final List<View> chips = new ArrayList<>();
    private Config cfg;
    private TvInputManager tv;
    private int applied = -1, appsGeneration;
    private boolean resumed, landed, unlockOpened;
    private FrameLayout root;
    private ImageView wallBack, wall;
    private View shade;
    private View tint;
    private TextView greeting, clock, date, freeRamLabel;
    private Meter meter;
    private Row apps, inputs;
    private ValueAnimator ambientAnim;
    private Map<String, Long> recent = new HashMap<>();
    private Tile moving;
    private String wallKey;
    private String backgroundMode;
    private ValueAnimator colorAnimation;
    private int displayedColor, targetColor;
    private boolean tintWallpaper;
    private int cycleCount;

    private final Runnable wallpaperCycler = new Runnable() {
        @Override
        public void run() {
            if (!resumed) return;
            List<File> list = cfg.wallpapers();
            if (list.size() > 1) {
                cfg.nextWallpaper();
                background();
            }
            if (++cycleCount % 2 == 0) {
                File wDir = new File(cfg.dir, Config.WALLPAPERS);
                WallpaperFetcher.fetchRandom(Home.this, wDir, () -> ui.post(() -> {
                    cfg.nextWallpaper();
                    background();
                }));
            }
            ui.postDelayed(this, 25000);
        }
    };

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            Uri data = i.getData();
            loadApps(data == null ? null : data.getSchemeSpecificPart());
        }
    };

    private final BroadcastReceiver time = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            tick();
        }
    };

    private final TvInputManager.TvInputCallback inputWatch = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputStateChanged(String id, int state) {
            loadInputs();
        }

        @Override
        public void onInputAdded(String id) {
            loadInputs();
        }

        @Override
        public void onInputRemoved(String id) {
            loadInputs();
        }

        @Override
        public void onInputUpdated(String id) {
            loadInputs();
        }
    };

    private final class Row {
        final String kind;
        final TextView title;
        final HorizontalScrollView scroll;
        final LinearLayout strip;
        final Grid grid;
        List<Item> all = new ArrayList<>();

        Row(String kind, String name, LinearLayout parent) {
            this.kind = kind;
            int edge = dp(56), bleed = dp(14);
            title = Ui.text(Home.this, 11, Ui.FAINT, Ui.MEDIUM);
            title.setText(name.toUpperCase(Locale.ROOT));
            title.setLetterSpacing(0.14f);
            title.setPadding(edge, dp(10), edge, dp(2));
            strip = new LinearLayout(Home.this);
            strip.setClipChildren(false);
            strip.setClipToPadding(false);
            strip.setPadding(bleed, dp(6), bleed, dp(6));
            scroll = new HorizontalScrollView(Home.this) {
                /** Keeps the focused tile centered, except near either end of the row. */
                @Override
                protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) {
                    if (getChildCount() == 0) return 0;
                    int range = Math.max(0, getChildAt(0).getWidth() - (getWidth() - getPaddingLeft() - getPaddingRight()));
                    int target = Math.max(0, Math.min(range, r.centerX() - getWidth() / 2));
                    return target - getScrollX();
                }
            };
            scroll.setFocusable(false);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            scroll.setClipChildren(false);
            scroll.setClipToPadding(false);
            scroll.setPadding(edge - bleed, 0, edge - bleed, 0);
            scroll.addView(strip, new FrameLayout.LayoutParams(WRAP, WRAP));
            grid = new Grid(Home.this);
            grid.setPadding(edge, dp(6), edge, dp(6));
            parent.addView(title, new LinearLayout.LayoutParams(MATCH, WRAP));
            parent.addView(scroll, new LinearLayout.LayoutParams(MATCH, WRAP));
            parent.addView(grid, new LinearLayout.LayoutParams(MATCH, WRAP));
        }

        /** Whichever container currently holds the tiles, following Options › Layout. */
        ViewGroup tiles() {
            return "grid".equals(cfg.get(Config.LAYOUT)) ? grid : strip;
        }

        void setVisible(boolean on) {
            boolean gridMode = "grid".equals(cfg.get(Config.LAYOUT));
            title.setVisibility(on ? View.VISIBLE : View.GONE);
            scroll.setVisibility(on && !gridMode ? View.VISIBLE : View.GONE);
            grid.setVisibility(on && gridMode ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Future<List<Item>> catalog = io.submit(() -> Catalog.apps(this));
        cfg = Config.get(this);
        Future<Map<String, Long>> times = recentFor(Catalog.APPS) == null ? null
                : io.submit(() -> Recent.times(this));
        tv = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        setContentView(build());

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packages, f);
        Keeper.start(this);
        if (tv != null) tv.registerCallback(inputWatch, ui);
        firstApps(catalog, times);
        loadInputs();
    }

    /**
     * Waits for the app list and loads the art of the tiles that fit on screen, so the first
     * frame is complete. After a cold start that frame is what people see first: loading in the
     * background drew the Inputs row alone, focused HDMI 1 for ~0.3 s, then popped the apps and
     * their banners in.
     */
    private void firstApps(Future<List<Item>> catalog, Future<Map<String, Long>> times) {
        try {
            apps.all = catalog.get();
            if (times != null) recent = times.get();
        } catch (ExecutionException e) {
            loadApps(null);
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            loadApps(null);
            return;
        }
        int fit = getResources().getDisplayMetrics().widthPixels / (cardSize()[0] + dp(18)) + 1;
        for (Item it : Catalog.visible(cfg, Catalog.APPS, apps.all, recentFor(Catalog.APPS))) {
            if (fit-- <= 0) break;
            Catalog.art(getPackageManager(), it);
            it.artRequested = true;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        Guard.arrived(this);
        cfg.refresh();
        cfg.visited();
        if (cfg.version() != applied) apply();
        else background();
        refreshRecent();
        startAmbientWallpaperAnimation();
        updateFreeRamText();
        ui.removeCallbacks(wallpaperCycler);
        ui.postDelayed(wallpaperCycler, 20000);
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(time, f);
        tick();
    }

    @Override
    public void onEnterAnimationComplete() {
        Guard.shown();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        ui.removeCallbacks(wallpaperCycler);
        stopAmbientWallpaperAnimation();
        stopColorAnimation();
        targetColor = 0;
        unregisterReceiver(time);
        if (moving != null) stopMoving();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        if (tv != null) tv.unregisterCallback(inputWatch);
        io.shutdownNow();
        colorIo.shutdownNow();
        super.onDestroy();
    }

    /**
     * Android 9 keeps a hidden window's GPU context (about 35 MB on this TV) unless memory is
     * critical. Dropping it while an app plays costs one context rebuild on the way back.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level < TRIM_MEMORY_UI_HIDDEN || level >= TRIM_MEMORY_COMPLETE) return;
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Object global = wmg.getMethod("getInstance").invoke(null);
            wmg.getMethod("trimMemory", int.class).invoke(global, TRIM_MEMORY_COMPLETE);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // not reachable on this build: the context simply stays
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (moving != null) stopMoving();
        if (resumed) focusFirst();
    }

    @Override
    public void onBackPressed() {
        focusFirst();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            int code = e.getKeyCode();
            if (handleHotKey(code)) return true;
        }
        if (moving == null) return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    shift(-1);
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    shift(1);
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    // In a grid, up and down move the tile a whole line; a strip has no lines.
                    if (moving.getParent() instanceof Grid) {
                        int cols = ((Grid) moving.getParent()).columns();
                        shift(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP ? -cols : cols);
                    }
                    break;
                default:
                    stopMoving();
            }
        }
        return true;
    }

    private boolean launchPackage(String pkg) {
        try {
            Intent intent = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (intent == null) intent = getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                startActivity(intent);
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private boolean handleHotKey(int code) {
        switch (code) {
            case 193: // Xiaomi / Android TV Netflix key
            case KeyEvent.KEYCODE_BUTTON_1:
            case KeyEvent.KEYCODE_PROG_RED:
                return launchPackage("com.netflix.ninja");
            case 194: // Xiaomi / Android TV Prime Video key
            case KeyEvent.KEYCODE_BUTTON_2:
            case KeyEvent.KEYCODE_PROG_GREEN:
                return launchPackage("com.amazon.amazonvideo.livingroom")
                        || launchPackage("com.amazon.avod.thirdpartyclient");
            case 192: // Xiaomi / Android TV Hotstar key
            case KeyEvent.KEYCODE_BUTTON_3:
            case KeyEvent.KEYCODE_PROG_YELLOW:
                return launchPackage("in.startv.hotstar");
            case 195: // Xiaomi / Android TV YouTube key
            case KeyEvent.KEYCODE_BUTTON_4:
            case KeyEvent.KEYCODE_PROG_BLUE:
                return launchPackage("com.google.android.youtube.tv");
            default:
                return false;
        }
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (handleHotKey(code)) return true;
        if (code == KeyEvent.KEYCODE_MENU && locked()) {
            // Repeats keep arriving while the button is held; the first one past the hold opens it.
            if (e.getRepeatCount() == 0) {
                unlockOpened = false;
            } else if (!unlockOpened && e.getEventTime() - e.getDownTime() >= UNLOCK_MS) {
                unlockOpened = true;
                startActivity(new Intent(this, Options.class));
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_MENU) {
            View f = getCurrentFocus();
            if (f instanceof Tile) menu((Tile) f);
            else startActivity(new Intent(this, Options.class));
            return true;
        }
        return super.onKeyDown(code, e);
    }

    /**
     * The scroll position for a focused rect {@code r}: the earliest of the top, the apps row or
     * the inputs row that still shows the row holding {@code r} whole.
     */
    private int rowTarget(ScrollView page, Rect r) {
        View content = page.getChildAt(0);
        List<int[]> rows = rowBlocks(content);
        int[] at = null;
        for (int[] b : rows) {
            if (r.centerY() >= b[0]) at = b;
        }
        if (at == null) return 0;
        int range = Math.max(0, content.getHeight() - page.getHeight());
        return Math.max(0, Math.min(range, stopFor(at, rows, page.getHeight())));
    }

    /**
     * Title top and strip bottom of each visible row, in {@code content}'s coordinates. In grid
     * mode every line of tiles is its own block (the first one starting at the row title), so
     * a grid taller than the screen still scrolls a whole line at a time.
     */
    private List<int[]> rowBlocks(View content) {
        List<int[]> rows = new ArrayList<>();
        for (Row row : new Row[] {inputs, apps}) {
            if (row == null) continue;
            if (row.scroll.getVisibility() == View.VISIBLE) {
                rows.add(new int[] {topIn(row.title, content), topIn(row.scroll, content) + row.scroll.getHeight()});
            } else if (row.grid.getVisibility() == View.VISIBLE) {
                int gridTop = topIn(row.grid, content), lastTop = Integer.MIN_VALUE;
                for (int i = 0; i < row.grid.getChildCount(); i++) {
                    View t = row.grid.getChildAt(i);
                    if (t.getTop() == lastTop) continue;
                    int start = lastTop == Integer.MIN_VALUE ? topIn(row.title, content) : gridTop + t.getTop() - dp(12);
                    lastTop = t.getTop();
                    rows.add(new int[] {start, gridTop + t.getBottom() + dp(8)});
                }
            }
        }
        return rows;
    }

    /** The earliest of the top or a row title that shows the whole of {@code at}. */
    private static int stopFor(int[] at, List<int[]> rows, int height) {
        if (height >= at[1]) return 0;
        for (int[] b : rows) {
            if (b[0] <= at[0] && b[0] + height >= at[1]) return b[0];
        }
        return at[0];
    }

    /**
     * When the page is taller than the screen, pads its bottom just enough that every row's stop
     * is reachable. Otherwise the last stop is clamped and the clock above it shows half cut.
     */
    private void fitBottom(ScrollView page) {
        View content = page.getChildAt(0);
        if (content == null || page.getHeight() == 0) return;
        int base = dp(32), height = page.getHeight();
        int natural = content.getHeight() - content.getPaddingBottom() + base;
        int want = base;
        if (natural > height) {
            int last = 0;
            List<int[]> rows = rowBlocks(content);
            for (int[] b : rows) last = Math.max(last, stopFor(b, rows, height));
            want = base + Math.max(0, last + height - natural);
        }
        if (content.getPaddingBottom() != want) {
            content.setPadding(content.getPaddingLeft(), content.getPaddingTop(), content.getPaddingRight(), want);
        }
    }

    /** {@code v}'s top edge in {@code content}'s coordinates. */
    private static int topIn(View v, View content) {
        int y = 0;
        while (v != null && v != content) {
            y += v.getTop();
            v = (View) v.getParent();
        }
        return y;
    }

    private View build() {
        root = new FrameLayout(this);
        root.setKeepScreenOn(true);
        wallBack = new ImageView(this);
        wallBack.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(wallBack, new FrameLayout.LayoutParams(MATCH, MATCH));
        wall = new ImageView(this);
        wall.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(wall, new FrameLayout.LayoutParams(MATCH, MATCH));
        shade = new View(this);
        root.addView(shade, new FrameLayout.LayoutParams(MATCH, MATCH));
        tint = new View(this);
        root.addView(tint, new FrameLayout.LayoutParams(MATCH, MATCH));

        ScrollView scroll = new ScrollView(this) {
            /** Scrolls in whole rows: the focused row shows complete, and a row starts at the top edge. */
            @Override
            protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) {
                return rowTarget(this, r) - getScrollY();
            }

            @Override
            protected void onLayout(boolean changed, int l, int t, int r, int b) {
                super.onLayout(changed, l, t, r, b);
                fitBottom(this);
            }

            /**
             * Up and down only move the focus; scrolling follows it. Left to ScrollView, a press
             * with nothing focusable that way scrolls half a screen and leaves rows cut.
             */
            @Override
            public boolean executeKeyEvent(KeyEvent e) {
                switch (e.getKeyCode()) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                    case KeyEvent.KEYCODE_PAGE_UP:
                    case KeyEvent.KEYCODE_PAGE_DOWN:
                    case KeyEvent.KEYCODE_SPACE:
                        return false;
                    default:
                        return super.executeKeyEvent(e);
                }
            }
        };
        scroll.setFocusable(false);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipChildren(false);
        root.addView(scroll, new FrameLayout.LayoutParams(MATCH, MATCH));

        LinearLayout column = new LinearLayout(this) {
            @Override
            public View focusSearch(View focused, int direction) {
                if (direction == View.FOCUS_DOWN && chips.contains(focused)) {
                    ViewGroup target = (inputs != null && inputs.tiles().getChildCount() > 0)
                            ? inputs.tiles() : (apps != null ? apps.tiles() : null);
                    if (target != null) {
                        View below = nearest(target, focused);
                        if (below != null) return below;
                    }
                }
                if (direction == View.FOCUS_UP && apps != null && apps.tiles().indexOfChild(focused) >= 0) {
                    if (inputs != null && inputs.tiles().getChildCount() > 0) {
                        View above = nearest(inputs.tiles(), focused);
                        if (above != null) return above;
                    }
                }
                if (direction == View.FOCUS_DOWN && inputs != null && inputs.tiles().indexOfChild(focused) >= 0) {
                    if (apps != null && apps.tiles().getChildCount() > 0) {
                        View below = nearest(apps.tiles(), focused);
                        if (below != null) return below;
                    }
                }
                return super.focusSearch(focused, direction);
            }
        };
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        column.setPadding(0, dp(28), 0, dp(24));
        scroll.addView(column, new FrameLayout.LayoutParams(MATCH, MATCH));

        LinearLayout head = new LinearLayout(this);
        head.setClipChildren(false);
        head.setClipToPadding(false);
        head.setGravity(Gravity.TOP);
        head.setPadding(dp(56), 0, dp(56), dp(8));
        LinearLayout when = new LinearLayout(this);
        when.setOrientation(LinearLayout.VERTICAL);
        greeting = Ui.text(this, 13, Ui.MUTED, Ui.MEDIUM);
        greeting.setLetterSpacing(0.12f);
        greeting.setPadding(dp(2), 0, 0, dp(4));
        when.addView(greeting);
        clock = Ui.text(this, 50, Ui.TEXT, Ui.LIGHT);
        date = Ui.text(this, 14, Ui.MUTED, Ui.MEDIUM);
        date.setPadding(dp(2), dp(6), 0, 0);
        when.addView(clock);
        when.addView(date);
        meter = new Meter(this);
        meter.setVisibility(View.GONE);
        LinearLayout.LayoutParams under = new LinearLayout.LayoutParams(WRAP, WRAP);
        under.topMargin = dp(10);
        under.leftMargin = dp(2);
        when.addView(meter, under);
        head.addView(when, new LinearLayout.LayoutParams(0, WRAP, 1f));
        head.addView(rocketChip(v -> boostMemory()));
        head.addView(iconChip("⚙", v -> openSettings()));
        column.addView(head, new LinearLayout.LayoutParams(MATCH, WRAP));

        View spacer = new View(this);
        column.addView(spacer, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        inputs = new Row(Catalog.INPUTS, "Inputs", column);
        apps = new Row(Catalog.APPS, "Apps", column);
        return root;
    }

    /**
     * The child of {@code strip} horizontally closest to {@code from}, restricted to its top
     * visual row so a wrapped grid does not match a tile several rows further down.
     */
    private static View nearest(ViewGroup strip, View from) {
        int n = strip.getChildCount();
        if (n == 0) return null;
        int[] at = new int[2];
        from.getLocationOnScreen(at);
        int x = at[0] + from.getWidth() / 2;
        int[] ys = new int[n];
        int topY = Integer.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            strip.getChildAt(i).getLocationOnScreen(at);
            ys[i] = at[1];
            topY = Math.min(topY, at[1]);
        }
        View best = null;
        int bestGap = Integer.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            if (ys[i] != topY) continue;
            View c = strip.getChildAt(i);
            c.getLocationOnScreen(at);
            int gap = Math.abs(at[0] + c.getWidth() / 2 - x);
            if (gap < bestGap) {
                bestGap = gap;
                best = c;
            }
        }
        return best;
    }

    private boolean locked() {
        return "on".equals(cfg.get(Config.LOCK));
    }

    private static final class RocketView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        RocketView(Context c) {
            super(c);
            paint.setColor(0xFFF2F2F3);
            paint.setStyle(Paint.Style.FILL);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int size = Ui.dp(getContext(), 13);
            setMeasuredDimension(size, size);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth(), h = getHeight();
            float cx = w / 2f;
            path.reset();
            path.moveTo(cx, h * 0.08f);
            path.cubicTo(cx + w * 0.38f, h * 0.32f, cx + w * 0.30f, h * 0.68f, cx + w * 0.18f, h * 0.82f);
            path.lineTo(cx + w * 0.44f, h * 0.92f);
            path.lineTo(cx + w * 0.22f, h * 0.90f);
            path.lineTo(cx + w * 0.14f, h * 0.84f);
            path.lineTo(cx - w * 0.14f, h * 0.84f);
            path.lineTo(cx - w * 0.22f, h * 0.90f);
            path.lineTo(cx - w * 0.44f, h * 0.92f);
            path.lineTo(cx - w * 0.18f, h * 0.82f);
            path.cubicTo(cx - w * 0.30f, h * 0.68f, cx - w * 0.38f, h * 0.32f, cx, h * 0.08f);
            path.close();
            canvas.drawPath(path, paint);

            Paint window = new Paint(Paint.ANTI_ALIAS_FLAG);
            window.setColor(0xFF141518);
            canvas.drawCircle(cx, h * 0.40f, w * 0.10f, window);
        }
    }

    private void updateFreeRamText() {
        if (freeRamLabel == null) return;
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return;
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long freeMb = mi.availMem / (1024 * 1024);
        String text = freeMb >= 1024
                ? String.format(Locale.ROOT, "%.1f GB", freeMb / 1024.0f)
                : (freeMb + " MB");
        freeRamLabel.setText(text);
    }

    private View rocketChip(View.OnClickListener click) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(8), dp(3), dp(8), dp(3));
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnClickListener(click);

        freeRamLabel = Ui.text(this, 8.5f, Ui.MUTED, Ui.MEDIUM);
        freeRamLabel.setSingleLine(true);
        freeRamLabel.setMaxLines(1);
        freeRamLabel.setEllipsize(null);
        freeRamLabel.setGravity(Gravity.CENTER);
        updateFreeRamText();
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(WRAP, WRAP);
        v.addView(freeRamLabel, flp);

        RocketView rocket = new RocketView(this);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(WRAP, WRAP);
        rlp.topMargin = dp(1);
        v.addView(rocket, rlp);

        v.setOnFocusChangeListener((view, has) -> {
            view.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).setDuration(120).start();
            if (has) focusBackground(null);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.leftMargin = dp(8);
        lp.topMargin = dp(4);
        v.setLayoutParams(lp);
        chips.add(v);
        return v;
    }

    private View iconChip(String iconChar, View.OnClickListener click) {
        TextView v = Ui.text(this, 13, Ui.TEXT, Ui.LIGHT);
        v.setText(iconChar);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(9), dp(7), dp(9), dp(7));
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnClickListener(click);
        v.setOnFocusChangeListener((view, has) -> {
            view.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).setDuration(120).start();
            if (has) focusBackground(null);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.leftMargin = dp(8);
        lp.topMargin = dp(4);
        v.setLayoutParams(lp);
        chips.add(v);
        return v;
    }

    private void apply() {
        applied = cfg.version();
        int accent = cfg.accent();
        for (View c : chips) {
            Ui.focusStyle(c, accent, 0x14FFFFFF, dp(10));
            c.setVisibility(locked() ? View.GONE : View.VISIBLE);
        }
        meter.setVisibility("on".equals(cfg.get(Config.MEMORY)) ? View.VISIBLE : View.GONE);
        meter.setAccent(accent);
        render(inputs);
        render(apps);
        background();
        tick();
    }

    /** Rebuilds the app list, keeping loaded art for every package except {@code changed}. */
    private void loadApps(String changed) {
        int generation = ++appsGeneration;
        io.execute(() -> {
            List<Item> list = Catalog.apps(this);
            ui.post(() -> {
                if (generation != appsGeneration || isDestroyed()) return;
                for (Item fresh : list) {
                    for (Item old : apps.all) {
                        if (old.id.equals(fresh.id) && !old.id.equals(changed) && old.artRequested) {
                            fresh.banner = old.banner;
                            fresh.icon = old.icon;
                            fresh.artRequested = true;
                            fresh.backgroundColor = old.backgroundColor;
                        }
                    }
                }
                apps.all = list;
                render(apps);
            });
        });
    }

    private void loadInputs() {
        inputs.all = Catalog.inputs(this, tv);
        render(inputs);
    }

    /** The last-used times to sort {@code kind} by, or null when it keeps the user's order. */
    private Map<String, Long> recentFor(String kind) {
        return Catalog.APPS.equals(kind) && !"custom".equals(cfg.get(Config.ORDER)) ? recent : null;
    }

    /** With App order › Recently used, re-sorts the apps row on the way back home. */
    private void refreshRecent() {
        if (recentFor(Catalog.APPS) == null) return;
        io.execute(() -> {
            Map<String, Long> times = Recent.times(this);
            ui.post(() -> {
                if (isDestroyed() || recentFor(Catalog.APPS) == null) return;
                List<Item> before = Catalog.visible(cfg, Catalog.APPS, apps.all, recent);
                recent = times;
                if (!before.equals(Catalog.visible(cfg, Catalog.APPS, apps.all, recent))) render(apps);
            });
        });
    }

    private void updateRowScale(Row row, Tile focusedTile) {
        if (row == null) return;
        ViewGroup tiles = row.tiles();
        if (tiles == null) return;
        boolean rowHasFocus = focusedTile != null && focusedTile.getParent() == tiles;
        for (int i = 0; i < tiles.getChildCount(); i++) {
            View child = tiles.getChildAt(i);
            if (child instanceof Tile) {
                ((Tile) child).setRowFocusState(child == focusedTile, rowHasFocus);
            }
        }
    }

    private void render(Row row) {
        if (moving != null && moving.kind.equals(row.kind)) stopMoving();
        View focused = getCurrentFocus();
        String keep = focused instanceof Tile && ((Tile) focused).kind.equals(row.kind)
                ? ((Tile) focused).item.id : null;
        boolean gridMode = "grid".equals(cfg.get(Config.LAYOUT));
        ViewGroup target = gridMode ? row.grid : row.strip;
        row.strip.removeAllViews();
        row.grid.removeAllViews();

        boolean enabled = !Catalog.INPUTS.equals(row.kind) || "on".equals(cfg.get(Config.INPUTS));
        List<Item> shown = enabled ? Catalog.visible(cfg, row.kind, row.all, recentFor(row.kind))
                : new ArrayList<>();
        int[] size = cardSize();
        int w = Catalog.APPS.equals(row.kind) ? size[0] : dp(78);
        int h = Catalog.APPS.equals(row.kind) ? size[1] : dp(28);
        String labels = cfg.get(Config.LABELS);
        int accent = cfg.accent();
        int index = 0, restore = -1;
        for (Item it : shown) {
            Tile t = new Tile(this, it, row.kind, Catalog.label(cfg, it), w, h, labels, accent);
            t.setOnClickListener(v -> open((Tile) v));
            t.setOnFocusChangeListener((v, has) -> {
                if (has) {
                    updateRowScale(row, (Tile) v);
                } else {
                    v.post(() -> {
                        if (row != null && !row.tiles().hasFocus()) {
                            updateRowScale(row, null);
                        }
                    });
                }
                if (has) focusBackground(((Tile) v).item);
            });
            t.setOnLongClickListener(v -> {
                if (!locked()) menu((Tile) v);
                return true;
            });
            if (gridMode) {
                target.addView(t, new ViewGroup.LayoutParams(WRAP, WRAP));
            } else {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
                lp.rightMargin = dp(12);
                target.addView(t, lp);
            }
            if (it.id.equals(keep)) restore = index;
            index++;
            if (Catalog.APPS.equals(row.kind) && !it.artRequested) loadArt(it);
        }
        row.setVisible(!shown.isEmpty());
        if (restore >= 0) {
            target.getChildAt(restore).requestFocus();
        } else if (keep != null && target.getChildCount() > 0) {
            target.getChildAt(0).requestFocus();
        } else if (getCurrentFocus() == null || !landed) {
            focusFirst();
        }
    }

    private void loadArt(Item it) {
        it.artRequested = true;
        io.execute(() -> {
            Catalog.art(getPackageManager(), it);
            ui.post(() -> {
                ViewGroup tiles = apps.tiles();
                for (int i = 0; i < tiles.getChildCount(); i++) tiles.getChildAt(i).invalidate();
                if (focusedItem() == it) focusBackground(it);
            });
        });
    }

    private Item focusedItem() {
        View focused = getCurrentFocus();
        return focused instanceof Tile ? ((Tile) focused).item : null;
    }

    private void focusBackground(Item it) {
        // Disabled: background wallpaper remains pure still photography and does not tint or change colors on app scroll
    }

    private void setAdaptiveColor(int color) {
        displayedColor = color;
        if ("adaptive-tint".equals(backgroundMode)) tint.setBackgroundColor(color);
        else root.setBackgroundColor(color);
    }

    private void stopColorAnimation() {
        if (colorAnimation != null) {
            colorAnimation.cancel();
            colorAnimation = null;
        }
    }

    private int[] cardSize() {
        switch (cfg.get(Config.TILES)) {
            case "small":
                return new int[] {dp(110), dp(62)};
            case "large":
                return new int[] {dp(160), dp(90)};
            default:
                return new int[] {dp(130), dp(73)};
        }
    }

    private void focusFirst() {
        if (inputs != null) inputs.scroll.scrollTo(0, 0);
        if (apps != null) apps.scroll.scrollTo(0, 0);
        View first = (apps != null && apps.tiles().getChildCount() > 0) ? apps.tiles().getChildAt(0) : null;
        if (first == null && inputs != null && inputs.tiles().getChildCount() > 0) first = inputs.tiles().getChildAt(0);
        landed = apps != null && apps.tiles().getChildCount() > 0;
        if (first == null && !chips.isEmpty()) first = chips.get(0);
        if (first != null) first.requestFocus();
    }

    private void tick() {
        if (greeting != null) {
            java.util.Calendar cal = java.util.Calendar.getInstance();
            int hour = cal.get(java.util.Calendar.HOUR_OF_DAY);
            String greet;
            if (hour >= 5 && hour < 12) {
                greet = "GOOD MORNING";
            } else if (hour >= 12 && hour < 17) {
                greet = "GOOD AFTERNOON";
            } else if (hour >= 17 && hour < 22) {
                greet = "GOOD EVENING";
            } else {
                greet = "GOOD NIGHT";
            }
            greeting.setText(greet);
        }
        updateFreeRamText();
        String mode = cfg.get(Config.CLOCK);
        boolean h24 = "24h".equals(mode) || "auto".equals(mode) && DateFormat.is24HourFormat(this);
        Date now = new Date();
        if (h24) {
            clock.setText(DateFormat.format("HH:mm", now));
        } else {
            SpannableString half = new SpannableString(" " + DateFormat.format("a", now));
            half.setSpan(new RelativeSizeSpan(0.36f), 0, half.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            clock.setText(DateFormat.format("h:mm", now));
            clock.append(half);
        }
        Locale locale = Locale.getDefault();
        date.setText(DateFormat.format(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), now));
        if (meter.getVisibility() == View.VISIBLE) {
            meter.update((android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE));
        }
        // hourly and time-of-day rotations change while the screen stays open; a no-op otherwise
        background();
    }

    private void background() {
        String mode = cfg.get(Config.BACKGROUND);
        boolean image = "image".equals(mode) || "adaptive-tint".equals(mode);
        File file = image ? cfg.wallpaper() : null;
        tintWallpaper = "adaptive-tint".equals(mode) && file != null;
        if ("adaptive".equals(mode)) {
            if (!mode.equals(backgroundMode)) {
                stopColorAnimation();
                targetColor = 0;
                root.setBackgroundColor(FocusColor.FALLBACK);
                displayedColor = FocusColor.FALLBACK;
                tint.setBackgroundColor(Color.TRANSPARENT);
            }
            backgroundMode = mode;
            focusBackground(focusedItem());
        } else if ("adaptive-tint".equals(mode)) {
            root.setBackgroundColor(file == null ? cfg.background() : Color.BLACK);
            if (!mode.equals(backgroundMode) || file == null) {
                stopColorAnimation();
                targetColor = 0;
                tint.setBackgroundColor(Color.TRANSPARENT);
                displayedColor = Color.TRANSPARENT;
            }
            backgroundMode = mode;
            if (tintWallpaper) focusBackground(focusedItem());
        } else {
            stopColorAnimation();
            targetColor = 0;
            backgroundMode = mode;
            tint.setBackgroundColor(Color.TRANSPARENT);
            root.setBackgroundColor(cfg.background());
        }
        int dim = Integer.parseInt(cfg.get(Config.DIM));
        shade.setBackgroundColor(file == null ? Color.TRANSPARENT : Color.argb(dim * 255 / 100, 0, 0, 0));
        String key = file == null ? null : file.getPath() + ":" + file.lastModified();
        if (key == null ? wallKey == null : key.equals(wallKey)) return;
        wallKey = key;
        if (file == null) {
            if (wall != null) wall.setImageDrawable(null);
            if (wallBack != null) wallBack.setImageDrawable(null);
            return;
        }
        io.execute(() -> {
            Bitmap b = decode(file, getResources().getDisplayMetrics().widthPixels,
                    getResources().getDisplayMetrics().heightPixels);
            ui.post(() -> {
                if (key.equals(wallKey) && wall != null && b != null) {
                    wall.animate().cancel();
                    wall.setImageBitmap(b);
                    wall.setAlpha(0f);
                    wall.animate().alpha(1f).setDuration(800).withEndAction(() -> {
                        if (wallBack != null) wallBack.setImageBitmap(b);
                    }).start();
                }
            });
        });
    }

    private static Bitmap decode(File f, int w, int h) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= w && o.outHeight / (o.inSampleSize * 2) >= h) {
            o.inSampleSize *= 2;
        }
        o.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    /**
     * Fire OS answers the generic settings action with a stub that reopens Amazon's home, which
     * Guard then replaces with ours. The TV settings entry point opens the real settings screen.
     */
    private void openSettings() {
        Intent tvSettings = new Intent(Intent.ACTION_MAIN)
                .addCategory("android.intent.category.LEANBACK_SETTINGS");
        boolean found = tvSettings.resolveActivity(getPackageManager()) != null;
        launch(found ? tvSettings : new Intent(Settings.ACTION_SETTINGS), null);
    }

    private void boostMemory() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo miBefore = new ActivityManager.MemoryInfo();
        if (am != null) am.getMemoryInfo(miBefore);

        // 1. Force stop / kill background processes across all user installed apps & streaming services
        if (am != null) {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> appsList = pm.getInstalledApplications(0);
            String myPkg = getPackageName();
            for (ApplicationInfo ai : appsList) {
                if (ai.packageName.equals(myPkg) || ai.packageName.startsWith("com.android.systemui")
                        || ai.packageName.equals("android")) {
                    continue;
                }
                try {
                    am.killBackgroundProcesses(ai.packageName);
                } catch (Throwable ignored) {}
            }
        }

        // 2. Clear app and system cache in background thread
        io.execute(() -> {
            try {
                deleteDir(getCacheDir());
                deleteDir(getCodeCacheDir());
                File ext = getExternalCacheDir();
                if (ext != null) deleteDir(ext);
            } catch (Throwable ignored) {}
            try {
                Runtime.getRuntime().exec(new String[]{"am", "kill-all"});
            } catch (Throwable ignored) {}
            try {
                Runtime.getRuntime().exec(new String[]{"pm", "trim-caches", "999999999999"});
            } catch (Throwable ignored) {}
        });

        // 3. Hardware & JVM Garbage Collection
        System.gc();
        Runtime.getRuntime().runFinalization();
        System.gc();
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Object global = wmg.getMethod("getInstance").invoke(null);
            wmg.getMethod("trimMemory", int.class).invoke(global, 80);
        } catch (Throwable ignored) {}

        ActivityManager.MemoryInfo miAfter = new ActivityManager.MemoryInfo();
        if (am != null) am.getMemoryInfo(miAfter);

        long freedMb = (miAfter.availMem - miBefore.availMem) / (1024 * 1024);
        String msg = freedMb > 0
                ? "🚀 Deep Cleaned! Freed " + freedMb + " MB RAM (" + (miAfter.availMem / (1024 * 1024)) + " MB free)"
                : "🚀 Deep Cleaned! " + (miAfter.availMem / (1024 * 1024)) + " MB free (Cache & Apps cleared)";
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        updateFreeRamText();
        if (meter != null && "on".equals(cfg.get(Config.MEMORY))) {
            meter.invalidate();
        }
    }

    private static void deleteDir(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteDir(f);
                else f.delete();
            }
        }
    }

    private void open(Tile t) {
        if (Catalog.APPS.equals(t.kind)) {
            launch(new Intent(t.item.intent), t.item.id);
            return;
        }
        Intent own = Input.intent(this, cfg, t.item, Catalog.label(cfg, t.item));
        // The built-in screen goes on top of this one in the same task, so BACK returns here and
        // the HOME takeover's singleTask relaunch clears it. NEW_TASK | RESET_TASK_IF_NEEDED on an
        // activity of the task that is already in front only brings that task forward and starts
        // nothing (ActivityStarter.complyActivityFlags), so it is started plainly.
        if (own != null) startActivity(own);
        else launch(new Intent(t.item.intent), null);
    }

    private void launch(Intent i, String pkg) {
        if (Guard.AMAZON.equals(pkg)) Guard.pause();
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            if (pkg != null) Recent.opened(this, pkg);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Could not open that", Toast.LENGTH_SHORT).show();
        }
    }

    private void menu(Tile t) {
        boolean app = Catalog.APPS.equals(t.kind);
        String[] actions = app
                ? new String[] {"Move", "Rename", "Hide", "App info"}
                : new String[] {"Move", "Rename", "Hide"};
        new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle(Catalog.label(cfg, t.item))
                .setItems(actions, (d, which) -> {
                    switch (which) {
                        case 0:
                            if (t.getParent() == null) break;
                            if (app && recentFor(t.kind) != null) {
                                Toast.makeText(this, "App order is Recently used. Pick Your order "
                                        + "in Options to move tiles.", Toast.LENGTH_LONG).show();
                                break;
                            }
                            moving = t;
                            t.setMoving(true);
                            break;
                        case 1:
                            rename(t);
                            break;
                        case 2:
                            Catalog.setShown(cfg, t.kind, t.item, false);
                            render(app ? apps : inputs);
                            Toast.makeText(this, "Hidden. Bring it back from Options.", Toast.LENGTH_SHORT).show();
                            break;
                        default:
                            launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", t.item.id, null)), null);
                    }
                })
                .show();
    }

    private void rename(Tile t) {
        EditText field = new EditText(this);
        field.setSingleLine();
        field.setText(Catalog.label(cfg, t.item));
        field.setSelectAllOnFocus(true);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(field);
        String key = "name." + t.item.id;
        AlertDialog d = new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle("Rename")
                .setView(box)
                .setPositiveButton("Save", (dialog, w) -> {
                    String name = field.getText().toString().trim();
                    cfg.set(key, name.equals(t.item.label.toString()) ? null : name);
                    apply();
                })
                .setNeutralButton("Original", (dialog, w) -> {
                    cfg.set(key, null);
                    apply();
                })
                .setNegativeButton("Cancel", null)
                .create();
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
    }

    private void shift(int by) {
        ViewGroup strip = (ViewGroup) moving.getParent();
        int from = strip.indexOfChild(moving), to = from + by;
        if (to < 0 || to >= strip.getChildCount()) return;
        ViewGroup.LayoutParams lp = moving.getLayoutParams();
        strip.removeViewAt(from);
        strip.addView(moving, to, lp);
        moving.requestFocus();
    }

    private void stopMoving() {
        Tile t = moving;
        moving = null;
        t.setMoving(false);
        ViewGroup strip = (ViewGroup) t.getParent();
        if (strip == null) return;
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < strip.getChildCount(); i++) ids.add(((Tile) strip.getChildAt(i)).item.id);
        Catalog.saveOrder(cfg, t.kind, ids);
        applied = cfg.version();
    }

    private void startAmbientWallpaperAnimation() {
        stopAmbientWallpaperAnimation();
        if (wall == null) return;
        ambientAnim = ValueAnimator.ofFloat(0f, 1f);
        ambientAnim.setDuration(30000);
        ambientAnim.setRepeatMode(ValueAnimator.REVERSE);
        ambientAnim.setRepeatCount(ValueAnimator.INFINITE);
        ambientAnim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        ambientAnim.addUpdateListener(a -> {
            float p = (Float) a.getAnimatedValue();
            float tx = (p - 0.5f) * dp(32);
            float ty = (float) Math.sin(p * Math.PI) * dp(12) - dp(6);
            if (wall != null) {
                wall.setScaleX(1.08f);
                wall.setScaleY(1.08f);
                wall.setTranslationX(tx);
                wall.setTranslationY(ty);
            }
            if (wallBack != null) {
                wallBack.setScaleX(1.08f);
                wallBack.setScaleY(1.08f);
                wallBack.setTranslationX(tx);
                wallBack.setTranslationY(ty);
            }
        });
        ambientAnim.start();
    }

    private void stopAmbientWallpaperAnimation() {
        if (ambientAnim != null) {
            ambientAnim.cancel();
            ambientAnim = null;
        }
        if (wall != null) {
            wall.setScaleX(1.0f);
            wall.setScaleY(1.0f);
            wall.setTranslationX(0f);
            wall.setTranslationY(0f);
        }
        if (wallBack != null) {
            wallBack.setScaleX(1.0f);
            wallBack.setScaleY(1.0f);
            wallBack.setTranslationX(0f);
            wallBack.setTranslationY(0f);
        }
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
