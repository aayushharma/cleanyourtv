package com.gathin.tvhome;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The "Clear memory" screen opened from the Settings panel: the apps you have used recently, each of which
 * you can close to free RAM, plus a "Close all" row. Android does not expose a true live list of background
 * apps to an ordinary app (locked down in Android 7), so this uses recent usage as the stand-in; those are
 * the apps actually holding memory. killBackgroundProcesses never touches the foreground app, so whatever is
 * playing keeps running. Same translucent, own-task style as QuickSettingsActivity.
 */
public final class RunningAppsActivity extends Activity {
    private static final long WINDOW_MS = 6L * 60 * 60 * 1000; // treat apps used in the last 6 hours as "recent"
    private static final int MAX_APPS = 8;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_quick_settings);
        findViewById(R.id.scrim).setOnClickListener(v -> finish());
        rebuild();
    }

    /** Anything else taking the screen closes the panel. */
    @Override
    protected void onPause() {
        super.onPause();
        finish();
        overridePendingTransition(0, 0);
    }

    private void rebuild() {
        LinearLayout rows = findViewById(R.id.rows);
        rows.removeAllViews();
        List<String> apps = recentApps();

        if (apps.isEmpty()) {
            View row = inflateRow(rows, null, getString(R.string.running_empty), null, false);
            row.setOnClickListener(v -> finish());
            rows.addView(row);
            row.requestFocus();
            return;
        }

        // "Close all apps" at the top, with current free RAM as its hint.
        View all = inflateRow(rows, getDrawable(R.drawable.ic_boost), getString(R.string.close_all_apps),
                getString(R.string.ram_free_fmt, freeRam()), false);
        all.setOnClickListener(v -> {
            long before = availMem();
            for (String p : apps) kill(p);
            toastFreed(availMem() - before);
            rebuild();
        });
        rows.addView(all);

        PackageManager pm = getPackageManager();
        for (String pkg : apps) {
            CharSequence label = pkg;
            Drawable icon = null;
            try {
                label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
                icon = pm.getApplicationIcon(pkg);
            } catch (Exception ignored) {
                // Fall back to the package name and no icon.
            }
            final CharSequence name = label;
            final String p = pkg;
            View row = inflateRow(rows, icon, label, null, true);
            row.setOnClickListener(v -> {
                long before = availMem();
                kill(p);
                long freed = availMem() - before;
                Toast.makeText(this, getString(R.string.app_closed_fmt, name,
                        Formatter.formatShortFileSize(this, availMem())), Toast.LENGTH_SHORT).show();
                rebuild();
            });
            rows.addView(row);
        }
        rows.getChildAt(0).requestFocus();
    }

    /**
     * Inflate a panel row. When {@code appIcon} is true the ImageView's pill tint is cleared so a real app
     * icon shows in its own colours; the generic glyph icons (ic_boost) keep the tint.
     */
    private View inflateRow(LinearLayout parent, Drawable icon, CharSequence title, String status, boolean appIcon) {
        View row = getLayoutInflater().inflate(R.layout.item_input, parent, false);
        ImageView iv = row.findViewById(R.id.icon);
        if (appIcon) iv.setImageTintList(null);
        if (icon != null) iv.setImageDrawable(icon); else iv.setVisibility(View.GONE);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        TextView hint = row.findViewById(R.id.status);
        if (status != null) hint.setText(status); else hint.setVisibility(View.GONE);
        return row;
    }

    /** Packages used within the recent window that have a launcher entry, newest first, excluding this app. */
    private List<String> recentApps() {
        Set<String> launchable = new HashSet<>();
        PackageManager pm = getPackageManager();
        for (String category : new String[]{Intent.CATEGORY_LAUNCHER, "android.intent.category.LEANBACK_LAUNCHER"}) {
            Intent main = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
                launchable.add(ri.activityInfo.packageName);
            }
        }
        String self = getPackageName();
        long now = System.currentTimeMillis();
        final Map<String, Long> lastUsed = new HashMap<>();
        UsageStatsManager usm = getSystemService(UsageStatsManager.class);
        if (usm != null) {
            List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - WINDOW_MS, now);
            if (stats != null) {
                for (UsageStats us : stats) {
                    String p = us.getPackageName();
                    if (p == null || p.equals(self) || !launchable.contains(p)) continue;
                    if (us.getLastTimeUsed() < now - WINDOW_MS) continue;
                    Long prev = lastUsed.get(p);
                    if (prev == null || us.getLastTimeUsed() > prev) lastUsed.put(p, us.getLastTimeUsed());
                }
            }
        }
        List<String> apps = new ArrayList<>(lastUsed.keySet());
        Collections.sort(apps, (a, b) -> Long.compare(lastUsed.get(b), lastUsed.get(a)));
        if (apps.size() > MAX_APPS) apps = new ArrayList<>(apps.subList(0, MAX_APPS));
        return apps;
    }

    private long availMem() {
        ActivityManager am = getSystemService(ActivityManager.class);
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(info);
        return info.availMem;
    }

    private String freeRam() {
        return Formatter.formatShortFileSize(this, availMem());
    }

    private void kill(String pkg) {
        ActivityManager am = getSystemService(ActivityManager.class);
        try {
            am.killBackgroundProcesses(pkg);
        } catch (Exception ignored) {
            // A system-protected package that refuses just stays.
        }
    }

    private void toastFreed(long freed) {
        String now = Formatter.formatShortFileSize(this, availMem());
        String msg = freed > 2L * 1024 * 1024
                ? getString(R.string.ram_freed, Formatter.formatShortFileSize(this, freed), now)
                : getString(R.string.ram_clean, now);
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
