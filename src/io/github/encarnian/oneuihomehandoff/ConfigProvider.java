package io.github.encarnian.oneuihomehandoff;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Binder;
import android.os.Process;
import android.content.pm.PackageManager;

/** Read-only settings bridge from the module's UI to the One UI Home process. */
public final class ConfigProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        enforceReader();
        if (!"readConfig".equals(method)) throw new IllegalArgumentException("Unsupported method");
        Bundle result = new Bundle();
        result.putBoolean("enabled", getContext().getSharedPreferences("settings", 0).getBoolean("enabled", true));
        result.putBoolean("diagnostics", getContext().getSharedPreferences("settings", 0).getBoolean("diagnostics", false));
        result.putInt("openingMs", AnimationSettings.openingMs(getContext().getSharedPreferences("settings", 0).getInt("openingMs", AnimationSettings.OPEN_DEFAULT_MS)));
        result.putInt("closingPercent", AnimationSettings.closingPercent(getContext().getSharedPreferences("settings", 0).getInt("closingPercent", AnimationSettings.CLOSE_DEFAULT_PERCENT)));
        return result;
    }
    private void enforceReader() {
        int caller = Binder.getCallingUid();
        if (caller == Process.myUid()) return;
        try {
            int homeUid = getContext().getPackageManager().getApplicationInfo(LauncherPolicy.SYSTEM_HOME, 0).uid;
            if (caller == homeUid) return;
        } catch (PackageManager.NameNotFoundException ignored) { }
        throw new SecurityException("Only this app and One UI Home may read configuration");
    }
    @Override public String getType(Uri uri) { return "vnd.android.cursor.item/vnd.io.github.encarnian.oneuihomehandoff.config"; }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { throw new UnsupportedOperationException(); }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
}
