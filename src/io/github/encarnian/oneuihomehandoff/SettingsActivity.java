package io.github.encarnian.oneuihomehandoff;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.SeekBar;
import android.widget.TextView;

/** Handoff, closing pace and opt-in diagnostics. */
public final class SettingsActivity extends Activity {
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void text(LinearLayout parent, String content, int size) {
        TextView view = new TextView(this);
        view.setText(content); view.setTextSize(size);
        view.setPadding(0, dp(8), 0, dp(12)); parent.addView(view);
    }
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(20), dp(24), dp(24));
        scroll.addView(body); setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            body.setPadding(dp(24) + bars.left, dp(20) + bars.top, dp(24) + bars.right, dp(24) + bars.bottom);
            return insets;
        });
        scroll.requestApplyInsets();
        text(body, "One UI Home Handoff", 27);
        text(body, "Version " + BuildInfo.VERSION_NAME, 15);
        text(body, "Vibecoded · AI-assisted experimental software. Review the open source before enabling. Compatibility and safety cannot be guaranteed.", 14);
        text(body, "Responsive Home gestures and smooth app opening with a third-party launcher.", 17);
        SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
        Switch enabled = new Switch(this);
        enabled.setText("Enable handoff"); enabled.setTextSize(18);
        enabled.setPadding(0, dp(14), 0, dp(14));
        enabled.setChecked(prefs.getBoolean("enabled", true));
        enabled.setOnCheckedChangeListener((view, checked) -> prefs.edit().putBoolean("enabled", checked).apply());
        body.addView(enabled);
        text(body, "Keeps the app-closing movement visible, allows earlier Home interaction, and animates an app opened during that movement. Changes apply to the next Home transition.", 15);
        text(body, "Closing animation", 20);
        TextView closingLabel = new TextView(this);
        closingLabel.setTextSize(17); body.addView(closingLabel);
        SeekBar closing = new SeekBar(this);
        closing.setMin(AnimationSettings.CLOSE_MIN_PERCENT); closing.setMax(AnimationSettings.CLOSE_MAX_PERCENT);
        closing.setProgress(AnimationSettings.closingPercent(prefs.getInt("closingPercent", AnimationSettings.CLOSE_DEFAULT_PERCENT)));
        closingLabel.setText("Closing duration: " + closing.getProgress() + "%");
        closing.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                closingLabel.setText("Closing duration: " + value + "%");
                if (fromUser) prefs.edit().putInt("closingPercent", AnimationSettings.closingPercent(value)).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        body.addView(closing);
        text(body, "Higher values give gentler, longer closing motion. Duration is approximate because the movement uses springs. Choose 100% for the native closing pace. This control does not add a delay before accepting Home taps.", 14);
        text(body, "Compatibility", 20);
        try {
            PackageInfo home = getPackageManager().getPackageInfo("com.sec.android.app.launcher", 0);
            boolean supported = "18.0.07.53".equals(home.versionName) && home.getLongVersionCode() == 1800705053L;
            text(body, "One UI Home: " + home.versionName + " (" + home.getLongVersionCode() + ")\n" + (supported
                ? "Supported Home version detected."
                : "Unsupported Home version. The module leaves navigation unchanged."), 16);
        } catch (Exception error) {
            text(body, "One UI Home could not be detected. This module requires Samsung One UI Home 18.0.07.53 (1800705053).", 16);
        }
        text(body, "Set a third-party launcher as your default Home app. Enable this module in LSPosed with One UI Home as its only scope, then restart One UI Home once after installing or updating the module.", 15);
        text(body, "This screen shows your settings and app compatibility. It does not confirm that LSPosed has loaded the module.", 14);
        text(body, "Diagnostics", 20);
        Switch diagnostics = new Switch(this);
        diagnostics.setText("Diagnostic logging"); diagnostics.setTextSize(17);
        diagnostics.setPadding(0, dp(12), 0, dp(12));
        diagnostics.setChecked(prefs.getBoolean("diagnostics", false));
        diagnostics.setOnCheckedChangeListener((view, checked) -> prefs.edit().putBoolean("diagnostics", checked).apply());
        body.addView(diagnostics);
        text(body, "Off by default. Records temporary task identifiers, touch timing and animation cleanup for troubleshooting. App package names and touch coordinates are not recorded. Review logs before sharing them. Turn this off when finished.", 14);
        text(body, "To return to stock navigation, turn Enable handoff off. To remove the hooks, disable this module in LSPosed and restart One UI Home. No full reboot is needed to change these settings.", 15);
    }
}
