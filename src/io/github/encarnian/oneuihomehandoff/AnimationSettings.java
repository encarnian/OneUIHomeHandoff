package io.github.encarnian.oneuihomehandoff;

/** Shared bounds for the settings UI, read-only bridge and runtime. */
final class AnimationSettings {
    static final int OPEN_MIN_MS = 120;
    static final int OPEN_MAX_MS = 450;
    static final int OPEN_DEFAULT_MS = 260;
    static final int CLOSE_MIN_PERCENT = 100;
    static final int CLOSE_MAX_PERCENT = 175;
    static final int CLOSE_DEFAULT_PERCENT = 125;
    static int openingMs(int value) { return Math.max(OPEN_MIN_MS, Math.min(OPEN_MAX_MS, value)); }
    static int closingPercent(int value) { return Math.max(CLOSE_MIN_PERCENT, Math.min(CLOSE_MAX_PERCENT, value)); }
    static float closingMultiplier(int value) { return closingPercent(value) / 100f; }
    static float stiffness(float nativeValue, float durationMultiplier) {
        return nativeValue / (durationMultiplier * durationMultiplier);
    }
}
