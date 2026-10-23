package com.raygold.netflixdpad;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Remote-friendly setup screen for the RAY GOLD (Android 12) Projector.
 * All buttons support standard D-Pad focus out of the box.
 */
public class MainActivity extends Activity {

    private static final String PREFS_NAME = "NetflixDpadPrefs";
    private TextView statusBanner;
    private Button toggleScopeBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#141414"));
        root.setPadding(64, 48, 64, 48);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("Netflix D-Pad Helper (RAY GOLD Android 12)");
        title.setTextColor(Color.parseColor("#E50914"));
        title.setTextSize(26f);
        title.setPadding(0, 0, 0, 12);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Replaces annoying mouse-cursor navigation in mobile Netflix with smooth directional D-Pad focus, auto-scrolling rows, and one-click OK selection.");
        subtitle.setTextColor(Color.parseColor("#CCCCCC"));
        subtitle.setTextSize(15f);
        subtitle.setPadding(0, 0, 0, 28);
        root.addView(subtitle);

        statusBanner = new TextView(this);
        statusBanner.setTextSize(16f);
        statusBanner.setPadding(32, 20, 32, 20);
        root.addView(statusBanner);

        // Button 1: Open Android 12 Accessibility Settings
        Button enableBtn = createTvButton("1. Enable D-Pad Service in Accessibility Settings");
        enableBtn.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                Toast.makeText(this, "Select 'Netflix D-Pad Helper' and turn it ON", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "Open Settings -> Accessibility -> Netflix D-Pad Helper", Toast.LENGTH_LONG).show();
            }
        });
        root.addView(enableBtn);

        // Button 2: Launch Netflix directly
        Button launchNetflixBtn = createTvButton("2. Launch Netflix Now");
        launchNetflixBtn.setOnClickListener(v -> {
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage("com.netflix.mediaclient");
            if (launchIntent != null) {
                startActivity(launchIntent);
            } else {
                Toast.makeText(this, "Netflix app (com.netflix.mediaclient) not found.", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(launchNetflixBtn);

        // Button 3: Toggle Netflix-Only vs All Touch Apps
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        toggleScopeBtn = createTvButton("");
        updateScopeButtonLabel(prefs.getBoolean("allow_all_apps", false));
        toggleScopeBtn.setOnClickListener(v -> {
            boolean current = prefs.getBoolean("allow_all_apps", false);
            boolean next = !current;
            prefs.edit().putBoolean("allow_all_apps", next).apply();
            updateScopeButtonLabel(next);
        });
        root.addView(toggleScopeBtn);

        // Instructions footer
        TextView helpText = new TextView(this);
        helpText.setText(
                "Remote Controls Inside Netflix:\n" +
                "• D-Pad Arrows (Up/Down/Left/Right): Moves red highlight box between movies & buttons, and auto-scrolls at screen edges.\n" +
                "• OK / Enter Button: Clicks the highlighted movie or button.\n" +
                "• During Fullscreen Video: Left/Right skips -10s/+10s; OK brings up Play/Pause controls.\n" +
                "• Menu Key: Switches between Smart Card Focus and Virtual Grid Pointer."
        );
        helpText.setTextColor(Color.parseColor("#999999"));
        helpText.setTextSize(14f);
        helpText.setPadding(0, 32, 0, 0);
        root.addView(helpText);

        setContentView(root);
        enableBtn.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateServiceStatus();
    }

    private void updateScopeButtonLabel(boolean allowAll) {
        if (allowAll) {
            toggleScopeBtn.setText("3. Active Scope: All Apps (Click to restrict to Netflix only)");
        } else {
            toggleScopeBtn.setText("3. Active Scope: Netflix Only (Click to enable for all apps)");
        }
    }

    private Button createTvButton(String text) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(16f);
        btn.setAllCaps(false);
        btn.setFocusable(true);
        btn.setFocusableInTouchMode(true);
        btn.setPadding(36, 24, 36, 24);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(0, 16, 0, 8);
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

        // Initial background
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
            statusBanner.setText("STATUS: ACTIVE — Netflix D-Pad Helper is running! Open Netflix and use your directional keys.");
            statusBanner.setTextColor(Color.parseColor("#46D369"));
            bannerBg.setColor(Color.parseColor("#112A18"));
            bannerBg.setStroke(2, Color.parseColor("#46D369"));
        } else {
            statusBanner.setText("STATUS: NOT ENABLED — Click Step 1 below to turn on 'Netflix D-Pad Helper' in Accessibility Settings.");
            statusBanner.setTextColor(Color.parseColor("#FFA00A"));
            bannerBg.setColor(Color.parseColor("#2A1F11"));
            bannerBg.setStroke(2, Color.parseColor("#FFA00A"));
        }
        statusBanner.setBackground(bannerBg);
    }

    private boolean isAccessibilityServiceEnabled() {
        String expectedComponentName = getPackageName() + "/" + NetflixDpadService.class.getCanonicalName();
        String enabledServices = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabledServices)) return false;
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabledServices);
        while (splitter.hasNext()) {
            String component = splitter.next();
            if (component.equalsIgnoreCase(expectedComponentName)) {
                return true;
            }
        }
        return false;
    }
}
