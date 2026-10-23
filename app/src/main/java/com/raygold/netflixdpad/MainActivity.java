package com.raygold.netflixdpad;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final String PREFS_NAME = NetflixDpadService.PREFS_NAME;
    private TextView statusBanner;
    private TextView keyTestBanner;
    private Button toggleScopeBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#141414"));
        root.setPadding(60, 40, 60, 40);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("Netflix D-Pad Helper v2 (RAY GOLD Android 12)");
        title.setTextColor(Color.parseColor("#E50914"));
        title.setTextSize(24f);
        title.setPadding(0, 0, 0, 10);
        root.addView(title);

        statusBanner = new TextView(this);
        statusBanner.setTextSize(15f);
        statusBanner.setPadding(28, 16, 28, 16);
        root.addView(statusBanner);

        Button enableBtn = createTvButton("1. Open Accessibility Settings (Turn ON 'Netflix D-Pad Helper')");
        enableBtn.setOnClickListener(v -> openAccessibilitySettings());
        root.addView(enableBtn);

        Button launchNetflixBtn = createTvButton("2. Launch Netflix Now");
        launchNetflixBtn.setOnClickListener(v -> {
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage("com.netflix.mediaclient");
            if (launchIntent == null) {
                launchIntent = getPackageManager().getLaunchIntentForPackage("com.netflix.ninja");
            }
            if (launchIntent != null) {
                startActivity(launchIntent);
            } else {
                Toast.makeText(this, "Open Netflix from your projector home screen.", Toast.LENGTH_LONG).show();
            }
        });
        root.addView(launchNetflixBtn);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        toggleScopeBtn = createTvButton("");
        updateScopeButtonLabel(prefs.getBoolean(
                NetflixDpadService.KEY_ALLOW_ALL_APPS, NetflixDpadService.DEFAULT_ALLOW_ALL_APPS));
        toggleScopeBtn.setOnClickListener(v -> {
            boolean current = prefs.getBoolean(
                    NetflixDpadService.KEY_ALLOW_ALL_APPS, NetflixDpadService.DEFAULT_ALLOW_ALL_APPS);
            boolean next = !current;
            prefs.edit().putBoolean(NetflixDpadService.KEY_ALLOW_ALL_APPS, next).apply();
            updateScopeButtonLabel(next);
        });
        root.addView(toggleScopeBtn);

        keyTestBanner = new TextView(this);
        keyTestBanner.setText("Remote Key Tester: Press any arrow or OK button on your remote to verify keycodes...");
        keyTestBanner.setTextColor(Color.parseColor("#BBBBBB"));
        keyTestBanner.setTextSize(14f);
        keyTestBanner.setPadding(0, 24, 0, 0);
        root.addView(keyTestBanner);

        setContentView(root);
        enableBtn.requestFocus();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && keyTestBanner != null) {
            keyTestBanner.setText("Detected Remote Key: " + KeyEvent.keyCodeToString(event.getKeyCode())
                    + " (code " + event.getKeyCode() + ")");
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateServiceStatus();
    }

    /**
     * Projector firmware often lacks the standard accessibility settings screen, and the old code
     * swallowed the failure so the button looked dead. Try several entry points and always tell
     * the user what happened.
     */
    private void openAccessibilitySettings() {
        Toast.makeText(this, "Opening Accessibility settings...", Toast.LENGTH_SHORT).show();

        Intent[] attempts = new Intent[] {
                new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                new Intent().setComponent(new ComponentName("com.android.settings",
                        "com.android.settings.accessibility.AccessibilitySettings")),
                new Intent().setComponent(new ComponentName("com.android.settings",
                        "com.android.settings.Settings$AccessibilitySettingsActivity")),
                new Intent(Settings.ACTION_SETTINGS),
        };
        for (Intent intent : attempts) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(intent);
                return;
            } catch (Exception ignored) {
                // Try the next entry point.
            }
        }
        Toast.makeText(this,
                "Could not open Settings. Open Settings > Accessibility on the projector and turn on "
                        + "'Netflix D-Pad Helper' manually.",
                Toast.LENGTH_LONG).show();
    }

    private void updateScopeButtonLabel(boolean allowAll) {
        if (allowAll) {
            toggleScopeBtn.setText("3. Active Mode: Universal (remote keys intercepted in ALL apps)");
        } else {
            toggleScopeBtn.setText("3. Active Mode: Netflix only (recommended)");
        }
    }

    private Button createTvButton(String text) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(15f);
        btn.setAllCaps(false);
        btn.setFocusable(true);
        btn.setFocusableInTouchMode(true);
        btn.setPadding(32, 20, 32, 20);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(0, 14, 0, 6);
        btn.setLayoutParams(lp);

        btn.setOnFocusChangeListener((View v, boolean hasFocus) -> {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(12f);
            if (hasFocus) {
                bg.setColor(Color.parseColor("#E50914"));
                bg.setStroke(4, Color.WHITE);
            } else {
                bg.setColor(Color.parseColor("#2B2B2B"));
                bg.setStroke(2, Color.parseColor("#444444"));
            }
            v.setBackground(bg);
        });

        GradientDrawable initialBg = new GradientDrawable();
        initialBg.setCornerRadius(12f);
        initialBg.setColor(Color.parseColor("#2B2B2B"));
        initialBg.setStroke(2, Color.parseColor("#444444"));
        btn.setBackground(initialBg);

        return btn;
    }

    private void updateServiceStatus() {
        boolean enabled = isAccessibilityServiceEnabled();
        GradientDrawable bannerBg = new GradientDrawable();
        bannerBg.setCornerRadius(10f);
        if (enabled) {
            statusBanner.setText("STATUS: ACTIVE — Open Netflix and use your directional arrow keys!");
            statusBanner.setTextColor(Color.parseColor("#46D369"));
            bannerBg.setColor(Color.parseColor("#112A18"));
            bannerBg.setStroke(2, Color.parseColor("#46D369"));
        } else {
            statusBanner.setText("STATUS: NOT ENABLED — Turn on 'Netflix D-Pad Helper' under Settings > Accessibility "
                    + "(Button 1 tries to open it; if nothing opens, go there manually).");
            statusBanner.setTextColor(Color.parseColor("#FFA00A"));
            bannerBg.setColor(Color.parseColor("#2A1F11"));
            bannerBg.setStroke(2, Color.parseColor("#FFA00A"));
        }
        statusBanner.setBackground(bannerBg);
    }

    private boolean isAccessibilityServiceEnabled() {
        ComponentName expected = new ComponentName(this, NetflixDpadService.class);
        String enabledServices = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabledServices)) return false;
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabledServices);
        while (splitter.hasNext()) {
            // Settings may store the short ("pkg/.Class") or full ("pkg/pkg.Class") form.
            ComponentName enabled = ComponentName.unflattenFromString(splitter.next());
            if (expected.equals(enabled)) {
                return true;
            }
        }
        return false;
    }
}
