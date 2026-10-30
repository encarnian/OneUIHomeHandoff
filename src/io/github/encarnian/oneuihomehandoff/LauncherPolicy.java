package io.github.encarnian.oneuihomehandoff;

/** Eligibility comes from the system's current default Home, not a launcher-brand list. */
final class LauncherPolicy {
    static final String SYSTEM_HOME = "com.sec.android.app.launcher";
    static final String MODULE = "io.github.encarnian.oneuihomehandoff";

    static boolean isThirdPartyHome(String name) {
        return name != null && name.indexOf('.') > 0 && !SYSTEM_HOME.equals(name)
            && !"com.android.systemui".equals(name) && !MODULE.equals(name);
    }

    static boolean matchesHome(String defaultHome, String appearedHome) {
        return isThirdPartyHome(defaultHome) && defaultHome.equals(appearedHome);
    }
}
