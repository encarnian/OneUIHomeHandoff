package io.github.encarnian.oneuihomehandoff;

import android.animation.ValueAnimator;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.WindowManager;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Third-party Home handoff, scoped to the supported One UI Home process and build. */
public final class HandoffHook implements IXposedHookLoadPackage {
    private static final String HOME = "com.sec.android.app.launcher";
    private static final String MANAGER = "com.honeyspace.gesture.recentsanimation.RecentsAnimationManager";
    private static final String SHELL = "com.honeyspace.transition.ShellTransitionManager";
    private static final String VERSION = "18.0.07.53";
    private static final long VERSION_CODE = 1800705053L;
    private static final String FRAME = "io.github.encarnian.oneuihomehandoff.frame";
    private final ThreadLocal<Frame> current = new ThreadLocal<>();
    private final ThreadLocal<Frame> fadeScope = new ThreadLocal<>();
    private final ThreadLocal<ClosingPace> closingPaceScope = new ThreadLocal<>();
    private final Map<Object, Float> pacedSprings = new WeakHashMap<>();
    private final ThreadLocal<Object> launchInterruptScope = new ThreadLocal<>();
    private final Map<Object, InputLease> inputLeases = new IdentityHashMap<>();
    private final Map<Object, OpeningSession> openingSessions = new IdentityHashMap<>();
    private final ThreadLocal<Boolean> ownAlphaWrite = new ThreadLocal<>();
    private final Map<Object, Object> pipTransitions = new IdentityHashMap<>();
    private final List<XC_MethodHook.Unhook> installed = new ArrayList<>();
    private volatile boolean ready;
    private volatile boolean broken;
    private volatile boolean diagnostics;
    private volatile int openingMs = AnimationSettings.OPEN_DEFAULT_MS;
    private volatile int closingPercent = AnimationSettings.CLOSE_DEFAULT_PERCENT;
    private volatile boolean closingPaceAvailable = true;
    private boolean configWarningShown;
    private ClassLoader loader;
    private long nextSerial;
    private HomeTrace homeTrace;

    private final class HomeTrace {
        final Object manager;
        final Object callbacks;
        final long serial = ++nextSerial;
        final long committed = SystemClock.uptimeMillis();
        long appeared = -1;
        long released = -1;
        HomeTrace(Object manager) {
            this.manager = manager;
            callbacks = XposedHelpers.getObjectField(manager, "stateCallbacks");
        }
    }

    private static final class Frame {
        Frame previous;
        boolean candidate;
        InputLease appLease;
        int openingTaskId = -1;
        Object openingTarget;
    }

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam load) throws Throwable {
        if (!HOME.equals(load.packageName) || !HOME.equals(load.processName)) return;
        loader = load.classLoader;
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (ready || broken) return;
                try {
                    Context context = (Context) param.args[0];
                    PackageInfo info = context.getPackageManager().getPackageInfo(HOME, 0);
                    if (!VERSION.equals(info.versionName) || VERSION_CODE != info.getLongVersionCode()) {
                        broken = true;
                        logImportant("UNSUPPORTED: " + info.versionName + "/" + info.getLongVersionCode() + "; no gesture hooks installed");
                        return;
                    }
                    install();
                    ready = true;
                    logImportant("READY v" + BuildInfo.VERSION_NAME + "; Home=" + VERSION + "; scope=launcher only");
                } catch (Throwable error) {
                    broken = true;
                    for (XC_MethodHook.Unhook hook : installed) hook.unhook();
                    installed.clear();
                    failure("install; no handoff changes will run", error);
                }
            }
        });
    }

    private void install() throws Throwable {
        Class<?> manager = XposedHelpers.findClass(MANAGER, loader);
        Class<?> target = XposedHelpers.findClass("android.view.RemoteAnimationTarget", loader);
        Class<?> targets = Array.newInstance(target, 0).getClass();
        Class<?> transition = XposedHelpers.findClass("android.window.TransitionInfo", loader);
        Class<?> leash = XposedHelpers.findClass("com.honeyspace.gesture.recentsanimation.LeashUpdater", loader);
        // Validate mutation targets before registering anything.
        XposedHelpers.findMethodExact(manager, "getEndTarget");
        XposedHelpers.findMethodExact(manager, "finishController", boolean.class, boolean.class);
        XposedHelpers.findMethodExact(manager, "getRoleComponentObserver");
        XposedHelpers.findMethodExact(manager, "isInDexDisplay");
        XposedHelpers.findMethodExact(manager, "canPipTransition", target);
        XposedHelpers.findField(manager, "startingInfo");
        Class<?> startInfo = XposedHelpers.findClass(MANAGER + "$GestureTransitionStartInfo", loader);
        XposedHelpers.findMethodExact(startInfo, "isDefaultHome");
        XposedHelpers.findMethodExact(startInfo, "getByKey");
        XposedHelpers.findField(XposedHelpers.findClass(SHELL, loader), "recentsAnimationController");
        Class<?> player = XposedHelpers.findClass("com.honeyspace.transition.anim.floating.PlayerImpl", loader);
        XposedHelpers.findMethodExact(player, "getCurrentWindowAlpha");
        XposedHelpers.findMethodExact(player, "cancelAnimation");
        XposedHelpers.findField(player, "isForward");
        Class<?> floating = XposedHelpers.findClass("com.honeyspace.transition.anim.floating.FloatingAnimator", loader);
        XposedHelpers.findMethodExact(floating, "getCurrentPlayer");
        Class<?> inputController = XposedHelpers.findClass("com.android.systemui.shared.system.InputConsumerController", loader);
        for (String name : new String[]{"isRegistered", "registerInputConsumer", "unregisterInputConsumer"})
            XposedHelpers.findMethodExact(inputController, name);
        Class<?> inputProxy = XposedHelpers.findClass("com.honeyspace.gesture.inputconsumer.InputConsumerProxy", loader);
        XposedHelpers.findMethodExact(inputProxy, "getController");
        installRestartAndPaceHooks();
        installed.add(XposedHelpers.findAndHookMethod(SurfaceControl.Transaction.class, "setAlpha",
            SurfaceControl.class, float.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!ready || Boolean.TRUE.equals(ownAlphaWrite.get())) return;
                    synchronized (inputLeases) {
                        for (InputLease lease : inputLeases.values()) {
                            if (!lease.opacityGuard || lease.closingLeash != param.args[0]) continue;
                            float requested = (Float) param.args[1];
                            if (!Float.isFinite(requested)) return;
                            lease.lastNativeAlpha = Math.max(0f, Math.min(1f, requested));
                            if (requested > lease.opacityCeiling) {
                                param.args[1] = lease.opacityCeiling;
                                lease.opacityClamps++;
                                if (!lease.opacityLogged) {
                                    lease.opacityLogged = true;
                                    log("CLOSING_ALPHA_CLAMP serial=" + lease.serial + " native=" + requested
                                        + " ceiling=" + lease.opacityCeiling);
                                }
                            }
                            return;
                        }
                    }
                }
            }));
        installed.add(XposedHelpers.findAndHookMethod(manager, "onAnimationCanceled", HashMap.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                OpeningSession session;
                synchronized (openingSessions) { session = openingSessions.get(param.thisObject); }
                if (session != null) session.finish("native cancellation", false);
            }
        }));
        installed.add(XposedHelpers.findAndHookMethod(manager, "startPipTransition", target, int.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!Boolean.TRUE.equals(param.getResult())) return;
                Object callbacks = XposedHelpers.getObjectField(param.thisObject, "stateCallbacks");
                synchronized (pipTransitions) { pipTransitions.put(param.thisObject, callbacks); }
                log("NATIVE_PIP_TRANSITION_STARTED; handoff changes skipped for this transition");
            }
        }));
        installed.add(XposedHelpers.findAndHookMethod(manager, "startHomeEntering", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                param.setObjectExtra("previousClosingPace", closingPaceScope.get());
                closingPaceScope.remove();
                if (!ready || broken) return;
                try {
                    if (XposedHelpers.getIntField(param.thisObject, "displayId") != 0
                        || XposedHelpers.getBooleanField(param.thisObject, "startFromLock")
                        || !isThirdPartyHome(param.thisObject)) return;
                    if (!readConfiguration((Context) XposedHelpers.getObjectField(param.thisObject, "context"))) return;
                    homeTrace = null;
                    if (diagnostics) logHomeSource(param.thisObject);
                    if (!eligibleCommittedHome(param.thisObject)) return;
                    if (closingPaceAvailable && ValueAnimator.getDurationScale() > 0f && closingPercent > 100)
                        closingPaceScope.set(new ClosingPace(param.thisObject, AnimationSettings.closingMultiplier(closingPercent)));
                    if (diagnostics) {
                        homeTrace = new HomeTrace(param.thisObject);
                        log("HOME_COMMITTED trace=" + homeTrace.serial);
                    }
                } catch (Throwable error) {
                    closingPaceScope.remove(); failure("Home entry preparation", error);
                }
            }
            @Override protected void afterHookedMethod(MethodHookParam param) {
                ClosingPace previous = (ClosingPace) param.getObjectExtra("previousClosingPace");
                if (previous == null) closingPaceScope.remove(); else closingPaceScope.set(previous);
            }
        }));
        Class<?> inputHandler = XposedHelpers.findClass("com.honeyspace.gesture.GestureInputHandler", loader);
        installed.add(XposedHelpers.findAndHookMethod(inputHandler, "onInputEvent", int.class, InputEvent.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!ready || broken || !diagnostics || ((Integer) param.args[0]) != 0 || !(param.args[1] instanceof MotionEvent)) return;
                MotionEvent event = (MotionEvent) param.args[1];
                int action = event.getActionMasked();
                if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_POINTER_DOWN) return;
                HomeTrace trace = homeTrace;
                if (trace == null || SystemClock.uptimeMillis() - trace.committed > 1500) return;
                try {
                    if (XposedHelpers.getObjectField(trace.manager, "stateCallbacks") != trace.callbacks
                        || !isThirdPartyHome(trace.manager)) return;
                    long eventTime = event.getEventTime();
                    log("TOUCH_DOWN trace=" + trace.serial + " sinceCommitMs=" + (eventTime - trace.committed)
                        + " sinceHomeMs=" + (trace.appeared < 0 ? "not-yet" : Long.toString(eventTime - trace.appeared))
                        + " sinceReleaseMs=" + (trace.released < 0 ? "not-yet" : Long.toString(eventTime - trace.released))
                        + " dispatchLagMs=" + (SystemClock.uptimeMillis() - eventTime) + " pointers=" + event.getPointerCount());
                } catch (Throwable error) { failure("touch timing observation", error); }
            }
        }));
        // Restore before the next transition starts, including entering Recents from Home.
        installed.add(XposedHelpers.findAndHookMethod(manager, "start", int.class, Point.class,
            boolean.class, boolean.class, boolean.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    finishOpeningSessions("before next gesture transition", true);
                    restoreInputLeases(null, "before next gesture transition");
                    synchronized (pipTransitions) { pipTransitions.remove(param.thisObject); }
                }
        }));
        // Samsung calls this after its native transition/focus completion flows settle.
        installed.add(XposedHelpers.findAndHookMethod(inputProxy, "end", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                restoreInputLeases(param.thisObject, "after native input cleanup");
            }
        }));

        // Complete the accompanying 300 ms Home fade before Samsung releases its surfaces.
        installed.add(XposedHelpers.findAndHookMethod(leash, "fadeInAppearedTargets", targets, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                param.setObjectExtra("previousFade", fadeScope.get());
                Frame frame = current.get();
                if (ready && !broken && frame != null && frame.candidate) fadeScope.set(frame);
                else fadeScope.remove();
            }
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Frame previous = (Frame) param.getObjectExtra("previousFade");
                if (previous == null) fadeScope.remove(); else fadeScope.set(previous);
            }
        }));
        installed.add(XposedHelpers.findAndHookMethod(ValueAnimator.class, "start", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!ready || broken) return;
                ValueAnimator animator = (ValueAnimator) param.thisObject;
                Frame fade = fadeScope.get();
                if (fade != null) {
                    animator.setDuration(0);
                    log("HOME_FADE_BYPASSED closingPathRetained=true");
                }
            }
        }));
        installed.add(XposedHelpers.findAndHookMethod(manager, "onTasksAppeared", targets, transition, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Frame frame = new Frame(); frame.previous = current.get();
                param.setObjectExtra(FRAME, frame); current.set(frame);
                if (!ready || broken) return;
                try {
                    frame.appLease = leaseFor(param.thisObject);
                    if (frame.appLease != null) {
                        frame.openingTarget = openingAppTarget(param.thisObject, param.args[0]);
                        if (frame.openingTarget != null)
                            frame.openingTaskId = XposedHelpers.getIntField(frame.openingTarget, "taskId");
                    }
                    boolean enabled = readConfiguration((Context) XposedHelpers.getObjectField(param.thisObject, "context"));
                    frame.candidate = enabled && eligible(param.thisObject, param.args[0]);
                    if (!frame.candidate) {
                        if (diagnostics && isThirdPartyHome(param.thisObject)) {
                            log("CALLBACK_SKIPPED id=" + XposedHelpers.getIntField(param.thisObject, "gestureTransitionId")
                                + " end=" + XposedHelpers.callMethod(param.thisObject, "getEndTarget")
                                + " display=" + XposedHelpers.getIntField(param.thisObject, "displayId")
                                + " fromHome=" + XposedHelpers.getBooleanField(param.thisObject, "startFromHome")
                                + " fromLock=" + XposedHelpers.getBooleanField(param.thisObject, "startFromLock"));
                        }
                        return;
                    }
                    if (homeTrace != null && homeTrace.manager == param.thisObject)
                        homeTrace.appeared = SystemClock.uptimeMillis();
                    log("HOME_APPEARED id=" + XposedHelpers.getIntField(param.thisObject, "gestureTransitionId"));
                } catch (Throwable error) {
                    frame.candidate = false;
                    broken = true;
                    failure("eligibility; handoff changes disabled for this process", error);
                }
            }
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Frame frame = (Frame) param.getObjectExtra(FRAME);
                try {
                    if (frame == null || param.hasThrowable() || broken) return;
                    if (frame.appLease != null && frame.openingTaskId >= 0) {
                        log("APP_TARGET_APPEARED serial=" + frame.appLease.serial
                            + " elapsedMs=" + (SystemClock.uptimeMillis() - frame.appLease.started)
                            + " taskId=" + frame.openingTaskId);
                        if (!animateHomeToApp(frame.appLease, frame.openingTarget)) interruptHomeForApp(frame.appLease);
                    }
                    if (frame.candidate && eligible(param.thisObject, param.args[0]))
                        releaseRegisteredInputConsumer(param.thisObject);
                } catch (Throwable error) {
                    broken = true;
                    failure("handoff action; handoff changes disabled for this process", error);
                } finally {
                    if (frame == null || frame.previous == null) current.remove(); else current.set(frame.previous);
                }
            }
        }));
        installed.add(XposedHelpers.findAndHookMethod(manager, "finishController", boolean.class, boolean.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!ready || broken) return;
                try {
                    OpeningSession opening;
                    synchronized (openingSessions) { opening = openingSessions.get(param.thisObject); }
                    if (opening != null && opening.sameTransition()) {
                        if (!opening.finishHeld) {
                            opening.finishHeld = true;
                            log("APP_OPEN_FINISH_HELD serial=" + opening.lease.serial);
                        }
                        param.setResult(null);
                        return;
                    }
                    if (launchInterruptScope.get() == param.thisObject) {
                        // The incoming opening task should win, rather than forcing Home to top.
                        param.args[0] = false; param.args[1] = false;
                    }
                    if (diagnostics && isThirdPartyHome(param.thisObject)) {
                        log("FINISH_CONTROLLER id=" + XposedHelpers.getIntField(param.thisObject, "gestureTransitionId")
                            + " toHome=" + param.args[0] + " userLeave=" + param.args[1]);
                    }
                } catch (Throwable ignored) { }
            }
        }));
    }

    private void installRestartAndPaceHooks() throws Throwable {
        Class<?> taskInfo = XposedHelpers.findClass("android.app.ActivityManager$RunningTaskInfo", loader);
        Class<?> taskListener = XposedHelpers.findClass("com.android.systemui.shared.system.TaskStackChangeListeners$Impl", loader);
        installed.add(XposedHelpers.findAndHookMethod(taskListener, "onActivityRestartAttempt", taskInfo,
            boolean.class, boolean.class, boolean.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!ready || broken || param.args[0] == null) return;
                    try {
                        int id = XposedHelpers.getIntField(param.args[0], "taskId");
                        InputLease lease = matchingRestartLease(id);
                        if (lease == null) return;
                        log("SAME_APP_RESTART_SIGNAL serial=" + lease.serial + " taskId=" + id
                            + " homeVisible=" + param.args[1] + " cleared=" + param.args[2] + " wasVisible=" + param.args[3]);
                        new Handler(Looper.getMainLooper()).post(() -> handleTaskRestart(lease, id));
                    } catch (Throwable error) { failure("same-task restart observation", error); }
                }
            }));

        Class<?> spring = XposedHelpers.findClass("com.honeyspace.transition.anim.RectFSpringAnim", loader);
        Class<?> config = XposedHelpers.findClass("com.honeyspace.transition.anim.RectFSpringAnim$SpringConfig", loader);
        for (String name : SpringSnapshot.FLOAT_FIELDS) XposedHelpers.findField(config, name);
        XposedHelpers.findField(config, "maxVelocityPxPerS");
        installed.add(XposedHelpers.findAndHookConstructor(spring, config, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                ClosingPace pace = closingPaceScope.get();
                if (!ready || broken || !closingPaceAvailable || pace == null || !pace.isCurrent()) return;
                SpringSnapshot snapshot = null;
                try {
                    snapshot = new SpringSnapshot(param.args[0]);
                    param.setObjectExtra("springSnapshot", snapshot);
                    snapshot.apply(pace.multiplier);
                    param.setObjectExtra("springMultiplier", pace.multiplier);
                } catch (Throwable error) {
                    closingPaceAvailable = false;
                    if (snapshot != null) {
                        try { snapshot.restore(); }
                        catch (Throwable restoreError) { failure("closing pace rollback", restoreError); }
                    }
                    param.setObjectExtra("springSnapshot", null);
                    failure("closing pace unavailable; native pace retained", error);
                }
            }
            @Override protected void afterHookedMethod(MethodHookParam param) {
                SpringSnapshot snapshot = (SpringSnapshot) param.getObjectExtra("springSnapshot");
                if (snapshot == null) return;
                try {
                    snapshot.restore();
                    if (!param.hasThrowable()) {
                        float multiplier = (Float) param.getObjectExtra("springMultiplier");
                        synchronized (pacedSprings) { pacedSprings.put(param.thisObject, multiplier); }
                    }
                } catch (Throwable error) {
                    closingPaceAvailable = false;
                    failure("closing pace configuration restoration", error);
                }
            }
        }));
        installed.add(XposedHelpers.findAndHookMethod(spring, "start", Context.class, PointF.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Float multiplier;
                synchronized (pacedSprings) { multiplier = pacedSprings.remove(param.thisObject); }
                if (multiplier == null || param.args[1] == null) return;
                PointF velocity = (PointF) param.args[1];
                param.args[1] = new PointF(velocity.x / multiplier, velocity.y / multiplier);
                log("CLOSING_SPRING_SLOWED multiplier=" + multiplier);
            }
        }));
    }

    private final class ClosingPace {
        final Object manager;
        final Object callbacks;
        final float multiplier;
        ClosingPace(Object manager, float multiplier) {
            this.manager = manager; this.multiplier = multiplier;
            callbacks = XposedHelpers.getObjectField(manager, "stateCallbacks");
        }
        boolean isCurrent() { return XposedHelpers.getObjectField(manager, "stateCallbacks") == callbacks; }
    }

    private static final class SpringSnapshot {
        static final String[] FLOAT_FIELDS = {"stiffnessX", "stiffnessY", "rectStiffness", "frictionXY"};
        final Object config;
        final float[] original = new float[FLOAT_FIELDS.length];
        final int maxVelocity;
        SpringSnapshot(Object config) {
            this.config = config;
            for (int i=0; i<FLOAT_FIELDS.length; i++) {
                original[i] = XposedHelpers.getFloatField(config, FLOAT_FIELDS[i]);
                if (!Float.isFinite(original[i]) || original[i] <= 0f)
                    throw new IllegalStateException("Invalid native spring tuning");
            }
            maxVelocity = XposedHelpers.getIntField(config, "maxVelocityPxPerS");
            if (maxVelocity <= 0) throw new IllegalStateException("Invalid native velocity cap");
        }
        void apply(float multiplier) {
            for (int i=0; i<3; i++)
                XposedHelpers.setFloatField(config, FLOAT_FIELDS[i], AnimationSettings.stiffness(original[i], multiplier));
            XposedHelpers.setFloatField(config, "frictionXY", original[3] / multiplier);
            XposedHelpers.setIntField(config, "maxVelocityPxPerS", Math.max(1, Math.round(maxVelocity / multiplier)));
        }
        void restore() {
            for (int i=0; i<FLOAT_FIELDS.length; i++) XposedHelpers.setFloatField(config, FLOAT_FIELDS[i], original[i]);
            XposedHelpers.setIntField(config, "maxVelocityPxPerS", maxVelocity);
        }
    }

    private InputLease matchingRestartLease(int taskId) {
        if (taskId <= 0) return null;
        synchronized (inputLeases) {
            for (InputLease lease : inputLeases.values()) {
                if (lease.closingTaskId == taskId && !lease.restartHandled
                    && XposedHelpers.getObjectField(lease.manager, "stateCallbacks") == lease.callbacks)
                    return lease;
            }
        }
        return null;
    }

    private void handleTaskRestart(InputLease lease, int taskId) {
        try {
            if (broken || lease.restartHandled || lease.closingTaskId != taskId
                || leaseFor(lease.manager) != lease) return;
            synchronized (openingSessions) { if (openingSessions.containsKey(lease.manager)) return; }
            if (!eligibleCommittedHome(lease.manager)) return;
            Object target = sourceTarget(lease.manager);
            if (target == null || XposedHelpers.getIntField(target, "taskId") != taskId) return;
            lease.restartHandled = true;
            log("SAME_APP_RESTART_HANDOFF serial=" + lease.serial + " taskId=" + taskId);
            if (!animateHomeToApp(lease, target)) interruptHomeForApp(lease);
        } catch (Throwable error) { failure("same-task restart handoff", error); }
    }

    private boolean readConfiguration(Context context) {
        try {
            Bundle result = context.getContentResolver().call(Uri.parse("content://io.github.encarnian.oneuihomehandoff.config"), "readConfig", null, null);
            if (result == null) throw new IllegalStateException("No settings returned");
            diagnostics = result.getBoolean("diagnostics", false);
            openingMs = AnimationSettings.openingMs(result.getInt("openingMs", AnimationSettings.OPEN_DEFAULT_MS));
            closingPercent = AnimationSettings.closingPercent(result.getInt("closingPercent", AnimationSettings.CLOSE_DEFAULT_PERCENT));
            configWarningShown = false;
            return result.getBoolean("enabled", true);
        } catch (Throwable error) {
            diagnostics = false;
            if (!configWarningShown) {
                configWarningShown = true;
                logImportant("CONFIG_UNAVAILABLE; new handoff changes remain inactive until settings can be read");
            }
            return false;
        }
    }

    private final class InputLease {
        final Object manager;
        final Object proxy;
        final Object controller;
        final Object callbacks;
        final long started = SystemClock.uptimeMillis();
        final long serial = ++nextSerial;
        final int openingDurationMs = openingMs;
        final float closingDurationMultiplier = AnimationSettings.closingMultiplier(closingPercent);
        boolean restartHandled;
        int restoreAttempts;
        SurfaceControl closingLeash;
        int closingTaskId = -1;
        boolean opacityGuard;
        boolean opacityLogged;
        int opacityClamps;
        float opacityCeiling = 0.79f;
        float lastNativeAlpha = 1f;
        InputLease(Object manager, Object proxy, Object controller) {
            this.manager = manager; this.proxy = proxy; this.controller = controller;
            callbacks = XposedHelpers.getObjectField(manager, "stateCallbacks");
        }
    }

    private void releaseRegisteredInputConsumer(Object manager) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            log("NATIVE_TOUCH_SKIPPED callback is not on the input receiver thread"); return;
        }
        Object proxy = XposedHelpers.getObjectField(manager, "inputConsumerProxy");
        Object controller = XposedHelpers.callMethod(proxy, "getController");
        if (controller == null) { log("NATIVE_TOUCH_SKIPPED no input consumer controller"); return; }
        synchronized (inputLeases) {
            if (inputLeases.containsKey(controller)) return;
            if (!Boolean.TRUE.equals(XposedHelpers.callMethod(controller, "isRegistered"))) {
                log("NATIVE_TOUCH_SKIPPED input consumer is already unregistered"); return;
            }
            InputLease lease = new InputLease(manager, proxy, controller);
            // Record before changing registration so exceptions retain a recovery path.
            inputLeases.put(controller, lease);
            Handler handler = new Handler(Looper.getMainLooper());
            long recoveryMs = Math.min(15000, Math.max(1500,
                Math.round(1500 * Math.max(1f, ValueAnimator.getDurationScale()) * lease.closingDurationMultiplier)));
            handler.postDelayed(() -> restoreInputLease(lease, "recovery deadline"), recoveryMs);
            try {
                beginClosingOpacityGuard(lease);
                XposedHelpers.callMethod(controller, "unregisterInputConsumer");
                boolean registered = Boolean.TRUE.equals(XposedHelpers.callMethod(controller, "isRegistered"));
                if (registered) throw new IllegalStateException("Input consumer remained registered");
                // A disposed receiver cannot own focus, and will no longer deliver a focus-loss event.
                Object companion = XposedHelpers.getStaticObjectField(XposedHelpers.findClass(SHELL, loader), "Companion");
                Object focusFlow = XposedHelpers.callMethod(companion, "getRecentsAnimInputConsumerFocusing");
                XposedHelpers.callMethod(focusFlow, "setValue", Boolean.FALSE);
                if (homeTrace != null && homeTrace.manager == manager) homeTrace.released = SystemClock.uptimeMillis();
                log("NATIVE_TOUCH_RELEASED serial=" + lease.serial + " registered="
                    + XposedHelpers.callMethod(controller, "isRegistered") + " closingPathRetained=true"
                    + " earlierClosingFade=" + lease.opacityGuard + " closingDurationMultiplier=" + lease.closingDurationMultiplier);
                if (diagnostics) Choreographer.getInstance().postFrameCallback(time -> {
                    try {
                        log("NATIVE_TOUCH_CHECK serial=" + lease.serial + " registeredAfterFrame="
                            + XposedHelpers.callMethod(controller, "isRegistered"));
                    } catch (Throwable error) { failure("input registration observation", error); }
                });
            } catch (Throwable error) {
                restoreInputLease(lease, "release failure");
                broken = true;
                failure("registered input release; handoff changes disabled", error);
            }
        }
    }

    private InputLease leaseFor(Object manager) {
        synchronized (inputLeases) {
            for (InputLease lease : inputLeases.values()) {
                if (lease.manager == manager && XposedHelpers.getObjectField(manager, "stateCallbacks") == lease.callbacks)
                    return lease;
            }
        }
        return null;
    }

    private Object sourceTarget(Object manager) {
        Object updater = XposedHelpers.getObjectField(manager, "leashUpdater");
        if (updater == null) return null;
        Object targets = XposedHelpers.callMethod(updater, "getTargets");
        return XposedHelpers.callMethod(targets, "findTask", XposedHelpers.getIntField(manager, "taskId"));
    }

    private void logHomeSource(Object manager) {
        Object source = sourceTarget(manager);
        if (source == null) { log("HOME_SOURCE unavailable=true"); return; }
        boolean allowed = XposedHelpers.getBooleanField(source, "allowEnterPip");
        boolean candidate = Boolean.TRUE.equals(XposedHelpers.callMethod(manager, "canPipTransition", source));
        log("HOME_SOURCE available=true pipAllowed=" + allowed + " nativePipCandidate=" + candidate);
    }

    private void beginClosingOpacityGuard(InputLease lease) {
        Object target = sourceTarget(lease.manager);
        if (target == null || XposedHelpers.getIntField(target, "mode") != 1) {
            log("CLOSING_ALPHA_SKIPPED serial=" + lease.serial + " source is not a closing target"); return;
        }
        Object config = XposedHelpers.getObjectField(target, "windowConfiguration");
        if (((Integer) XposedHelpers.callMethod(config, "getActivityType")) != 1) return;
        SurfaceControl surface = (SurfaceControl) XposedHelpers.getObjectField(target, "leash");
        if (surface == null || !surface.isValid()) return;
        Context context = (Context) XposedHelpers.getObjectField(lease.manager, "context");
        android.hardware.input.InputManager input = context.getSystemService(android.hardware.input.InputManager.class);
        if (input != null) lease.opacityCeiling = Math.max(0f, Math.min(0.79f, input.getMaximumObscuringOpacityForTouch() - 0.01f));
        Object floating = XposedHelpers.getObjectField(lease.manager, "floatingAnimator");
        Object player = XposedHelpers.callMethod(floating, "getCurrentPlayer");
        if (player != null) {
            float alpha = (Float) XposedHelpers.callMethod(player, "getCurrentWindowAlpha");
            if (Float.isFinite(alpha)) lease.lastNativeAlpha = Math.max(0f, Math.min(1f, alpha));
        }
        lease.closingLeash = surface;
        lease.closingTaskId = XposedHelpers.getIntField(target, "taskId");
        lease.opacityGuard = true;
        writeClosingAlpha(lease, Math.min(lease.lastNativeAlpha, lease.opacityCeiling));
        log("CLOSING_ALPHA_GUARD serial=" + lease.serial + " ceiling=" + lease.opacityCeiling
            + " originalAlpha=" + lease.lastNativeAlpha);
    }

    private void writeClosingAlpha(InputLease lease, float alpha) {
        if (lease.closingLeash == null || !lease.closingLeash.isValid()) return;
        Boolean previous = ownAlphaWrite.get(); ownAlphaWrite.set(Boolean.TRUE);
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            transaction.setAlpha(lease.closingLeash, alpha).apply();
        } finally {
            if (previous == null) ownAlphaWrite.remove(); else ownAlphaWrite.set(previous);
        }
    }

    private void endClosingOpacityGuard(InputLease lease, String reason) {
        if (!lease.opacityGuard) return;
        lease.opacityGuard = false;
        try { writeClosingAlpha(lease, lease.lastNativeAlpha); }
        catch (Throwable error) { failure("closing alpha restoration", error); }
        log("CLOSING_ALPHA_ENDED serial=" + lease.serial + " nativeUpdatesClamped="
            + lease.opacityClamps + " reason=" + reason);
    }

    private Object openingAppTarget(Object manager, Object appeared) {
        ComponentName home = thirdPartyHome(manager);
        if (appeared == null || home == null) return null;
        for (int i=0; i<Array.getLength(appeared); i++) {
            Object target = Array.get(appeared, i);
            if (target == null || XposedHelpers.getIntField(target, "mode") != 0) continue;
            Object leash = XposedHelpers.getObjectField(target, "leash");
            if (leash == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(leash, "isValid"))) continue;
            Object configuration = XposedHelpers.getObjectField(target, "windowConfiguration");
            if (((Integer) XposedHelpers.callMethod(configuration, "getActivityType")) != 1) continue;
            Object task = XposedHelpers.getObjectField(target, "taskInfo");
            if (task == null) continue;
            ComponentName top = (ComponentName) XposedHelpers.getObjectField(task, "topActivity");
            if (top == null || home.getPackageName().equals(top.getPackageName()) || HOME.equals(top.getPackageName())
                || "com.android.systemui".equals(top.getPackageName())) continue;
            int id = XposedHelpers.getIntField(target, "taskId");
            if (id > 0) return target;
        }
        return null;
    }

    private boolean animateHomeToApp(InputLease lease, Object target) {
        if (target == null || leaseFor(lease.manager) != lease || !eligibleCommittedHome(lease.manager)
            || Looper.myLooper() != Looper.getMainLooper()) return false;
        OpeningSession session = null;
        try {
            if (consumeExistingOpening(lease, target)) return true;
            float scale = ValueAnimator.getDurationScale();
            if (scale <= 0f) return false;
            Context context = (Context) XposedHelpers.getObjectField(lease.manager, "context");
            WindowManager windows = context.getSystemService(WindowManager.class);
            if (windows == null || windows.getDefaultDisplay().getRotation() != 0) {
                log("APP_OPEN_SKIPPED serial=" + lease.serial + " reason=rotated display"); return false;
            }
            Rect bounds = (Rect) XposedHelpers.getObjectField(target, "screenSpaceBounds");
            Rect local = (Rect) XposedHelpers.getObjectField(target, "localBounds");
            Object config = XposedHelpers.getObjectField(target, "windowConfiguration");
            // Only the verified unrotated, full-screen origin has a known identity final transform.
            if (bounds == null || local == null || bounds.isEmpty() || bounds.left != 0 || bounds.top != 0
                || local.left != 0 || local.top != 0 || bounds.height() <= bounds.width()
                || ((Integer) XposedHelpers.callMethod(config, "getWindowingMode")) != 1
                || XposedHelpers.getBooleanField(target, "isTranslucent")
                || XposedHelpers.getIntField(target, "rotationChange") != 0) {
                log("APP_OPEN_SKIPPED serial=" + lease.serial + " reason=nonstandard opening geometry"); return false;
            }
            Object floating = XposedHelpers.getObjectField(lease.manager, "floatingAnimator");
            Object player = XposedHelpers.callMethod(floating, "getCurrentPlayer");
            if (player != null && XposedHelpers.getBooleanField(player, "isForward")) {
                log("APP_OPEN_SKIPPED serial=" + lease.serial + " reason=native opening player"); return false;
            }
            session = new OpeningSession(lease, target, bounds, Math.min(600, Math.max(1, Math.round(lease.openingDurationMs * scale))));
            synchronized (openingSessions) { openingSessions.put(lease.manager, session); }
            endClosingOpacityGuard(lease, "animated incoming app");
            Object previous = launchInterruptScope.get(); launchInterruptScope.set(lease.manager);
            try {
                // Finish is held by the session until the app is visibly full size.
                if (player != null) XposedHelpers.callMethod(player, "cancelAnimation");
                XposedHelpers.callMethod(lease.manager, "finishController", false, false);
            } finally {
                if (previous == null) launchInterruptScope.remove(); else launchInterruptScope.set(previous);
            }
            if (!session.sameTransition()) {
                session.finish("controller changed during setup", false); return true;
            }
            session.draw(0f);
            session.schedule();
            log("APP_OPEN_STARTED serial=" + lease.serial + " taskId=" + session.taskId
                + " durationMs=" + session.durationMs + " startScale=0.84");
            return true;
        } catch (Throwable error) {
            failure("optional opening animation; falling back to immediate handoff", error);
            if (session != null) { session.finish("setup failure", true); return true; }
            return false;
        }
    }

    private boolean consumeExistingOpening(InputLease lease, Object target) {
        OpeningSession existing;
        synchronized (openingSessions) { existing = openingSessions.get(lease.manager); }
        if (existing == null) return false;
        if (existing.taskId == XposedHelpers.getIntField(target, "taskId")) {
            log("APP_OPEN_DUPLICATE_TARGET serial=" + lease.serial + " taskId=" + existing.taskId);
            return true;
        }
        existing.finish("another opening task", true);
        return true;
    }

    private void finishOpeningSessions(String reason, boolean finishController) {
        List<OpeningSession> sessions;
        synchronized (openingSessions) { sessions = new ArrayList<>(openingSessions.values()); }
        for (OpeningSession session : sessions) session.finish(reason, finishController);
    }

    private final class OpeningSession implements Choreographer.FrameCallback {
        final InputLease lease;
        final Object callbacks;
        final Object shell;
        final Object controller;
        final SurfaceControl surface;
        final SurfaceControl.Transaction transaction;
        final Rect crop;
        final int taskId;
        final long durationMs;
        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable watchdog = () -> finish("opening watchdog", true);
        long firstFrame;
        int frames;
        boolean finishHeld;
        boolean stopped;

        OpeningSession(InputLease lease, Object target, Rect bounds, long durationMs) throws Throwable {
            this.lease = lease; this.durationMs = durationMs;
            callbacks = lease.callbacks;
            shell = shellFor(lease.manager);
            controller = XposedHelpers.getObjectField(shell, "recentsAnimationController");
            if (controller == null) throw new IllegalStateException("Opening controller is already gone");
            surface = (SurfaceControl) XposedHelpers.getObjectField(target, "leash");
            if (surface == null || !surface.isValid()) throw new IllegalStateException("Opening surface is invalid");
            crop = new Rect(0, 0, bounds.width(), bounds.height());
            taskId = XposedHelpers.getIntField(target, "taskId");
            transaction = new SurfaceControl.Transaction();
        }

        boolean sameTransition() {
            return XposedHelpers.getObjectField(lease.manager, "stateCallbacks") == callbacks
                && XposedHelpers.getObjectField(shell, "recentsAnimationController") == controller;
        }

        void schedule() {
            handler.postDelayed(watchdog, durationMs + 350);
            Choreographer.getInstance().postFrameCallback(this);
        }

        void draw(float progress) {
            float remaining = 1f - progress;
            float eased = 1f - remaining * remaining * remaining;
            float scale = 0.84f + 0.16f * eased;
            transaction.setScale(surface, scale, scale)
                .setPosition(surface, crop.width() * (1f - scale) / 2f, crop.height() * (1f - scale) / 2f)
                .setCrop(surface, crop).setAlpha(surface, 1f).setVisibility(surface, true);
            // Binder can return another Java wrapper for the same task's existing leash.
            if (lease.closingTaskId != taskId && lease.closingLeash != null
                && lease.closingLeash != surface && lease.closingLeash.isValid())
                transaction.setVisibility(lease.closingLeash, false);
            transaction.apply();
        }

        @Override public void doFrame(long timeNanos) {
            if (stopped) return;
            try {
                if (!sameTransition()) { finish("controller changed", false); return; }
                if (!surface.isValid()) { finish("surface invalid", true); return; }
                if (firstFrame == 0) firstFrame = timeNanos;
                float progress = Math.min(1f, (timeNanos - firstFrame) / (durationMs * 1000000f));
                draw(progress); frames++;
                if (progress >= 1f) finish("animation complete", true);
                else Choreographer.getInstance().postFrameCallback(this);
            } catch (Throwable error) {
                failure("opening frame", error);
                finish("frame failure", true);
            }
        }

        void finish(String reason, boolean finishController) {
            if (stopped) return;
            stopped = true;
            synchronized (openingSessions) {
                if (openingSessions.get(lease.manager) == this) openingSessions.remove(lease.manager);
            }
            handler.removeCallbacks(watchdog);
            Choreographer.getInstance().removeFrameCallback(this);
            boolean current = false;
            try {
                current = sameTransition();
                if (current && surface.isValid()) {
                    // Normalize our geometry without overriding visibility chosen by a cancellation.
                    transaction.setScale(surface, 1f, 1f).setPosition(surface, 0f, 0f)
                        .setCrop(surface, null).apply();
                }
            } catch (Throwable error) { failure("opening transform cleanup", error); }
            finally {
                try { transaction.close(); } catch (Throwable error) { failure("opening transaction cleanup", error); }
            }
            log("APP_OPEN_ENDED serial=" + lease.serial + " frames=" + frames + " reason=" + reason);
            if (finishController && current) {
                try { XposedHelpers.callMethod(lease.manager, "finishController", false, false); }
                catch (Throwable error) { failure("opening controller cleanup", error); }
            }
        }
    }

    private void interruptHomeForApp(InputLease lease) {
        if (leaseFor(lease.manager) != lease || !eligibleCommittedHome(lease.manager)) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            log("APP_INTERRUPT_SKIPPED callback is not on the animation thread"); return;
        }
        Object previous = launchInterruptScope.get();
        try {
            Object floating = XposedHelpers.getObjectField(lease.manager, "floatingAnimator");
            Object player = XposedHelpers.callMethod(floating, "getCurrentPlayer");
            if (player != null && XposedHelpers.getBooleanField(player, "isForward")) {
                log("APP_INTERRUPT_SKIPPED serial=" + lease.serial + " native opening animation already active"); return;
            }
            endClosingOpacityGuard(lease, "incoming app");
            launchInterruptScope.set(lease.manager);
            log("APP_INTERRUPT_REQUEST serial=" + lease.serial + " elapsedMs=" + (SystemClock.uptimeMillis() - lease.started));
            // Native cancel clears the closing springs and invokes their existing cleanup callbacks.
            if (player != null) XposedHelpers.callMethod(player, "cancelAnimation");
            if (eligibleCommittedHome(lease.manager))
                XposedHelpers.callMethod(lease.manager, "finishController", false, false);
            log("APP_INTERRUPT_RETURN serial=" + lease.serial);
        } catch (Throwable error) {
            broken = true; failure("app launch interruption; handoff changes disabled", error);
        } finally {
            if (previous == null) launchInterruptScope.remove(); else launchInterruptScope.set(previous);
        }
    }

    private void restoreInputLeases(Object proxy, String reason) {
        List<InputLease> leases;
        synchronized (inputLeases) { leases = new ArrayList<>(inputLeases.values()); }
        for (InputLease lease : leases) {
            if (proxy == null || proxy == lease.proxy) restoreInputLease(lease, reason);
        }
    }

    private void restoreInputLease(InputLease lease, String reason) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            new Handler(Looper.getMainLooper()).post(() -> restoreInputLease(lease, reason)); return;
        }
        synchronized (inputLeases) {
            if (inputLeases.get(lease.controller) != lease) return;
            try {
                Object current = XposedHelpers.callMethod(lease.proxy, "getController");
                if (current != lease.controller) {
                    // The service replaced the controller; do not recreate a stale input channel.
                    endClosingOpacityGuard(lease, "controller replaced");
                    inputLeases.remove(lease.controller);
                    log("NATIVE_TOUCH_LEASE_ENDED serial=" + lease.serial + " controllerReplaced=true"); return;
                }
                if (!Boolean.TRUE.equals(XposedHelpers.callMethod(lease.controller, "isRegistered")))
                    XposedHelpers.callMethod(lease.controller, "registerInputConsumer");
                if (!Boolean.TRUE.equals(XposedHelpers.callMethod(lease.controller, "isRegistered")))
                    throw new IllegalStateException("Input consumer did not re-register");
                endClosingOpacityGuard(lease, reason);
                inputLeases.remove(lease.controller);
                log("NATIVE_TOUCH_RESTORED serial=" + lease.serial + " elapsedMs="
                    + (SystemClock.uptimeMillis() - lease.started) + " reason=" + reason);
            } catch (Throwable error) {
                broken = true;
                failure("input restoration", error);
                // Retry while retaining the lease, even if handoff actions were disabled.
                if (++lease.restoreAttempts <= 3)
                    new Handler(Looper.getMainLooper()).postDelayed(() -> restoreInputLease(lease, "recovery retry"), 100);
                else logImportant("INPUT_RESTORE_NEEDS_PROCESS_RESTART serial=" + lease.serial);
            }
        }
    }

    private Object shellFor(Object manager) throws Throwable {
        Object system = XposedHelpers.callMethod(manager, "getSystemController");
        Class<?> shellClass = XposedHelpers.findClass(SHELL, loader);
        Object match = null;
        // The HoneySystemController implementation is obfuscated; resolve its delegate by type.
        for (Class<?> type = system.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!field.getType().equals(shellClass)) continue;
                field.setAccessible(true);
                if (match != null) throw new IllegalStateException("Multiple ShellTransitionManager delegates");
                match = field.get(system);
            }
        }
        if (match == null) throw new IllegalStateException("No ShellTransitionManager delegate");
        return match;
    }

    private ComponentName thirdPartyHome(Object manager) {
        Object observer = XposedHelpers.callMethod(manager, "getRoleComponentObserver");
        if (observer == null) return null;
        Intent intent = (Intent) XposedHelpers.callMethod(observer, "getHomeIntentForGesture", 0);
        ComponentName component = intent == null ? null : intent.getComponent();
        return component != null && LauncherPolicy.isThirdPartyHome(component.getPackageName()) ? component : null;
    }

    private boolean isThirdPartyHome(Object manager) { return thirdPartyHome(manager) != null; }

    private boolean eligibleCommittedHome(Object manager) {
        if (XposedHelpers.getIntField(manager, "displayId") != 0
            || XposedHelpers.getBooleanField(manager, "startFromLock")
            || XposedHelpers.getBooleanField(manager, "startFromHome")
            || XposedHelpers.getBooleanField(manager, "isExternalDisplay")
            || XposedHelpers.getBooleanField(manager, "isMultiTargets")
            || XposedHelpers.getObjectField(manager, "desktopModeHelper") != null
            || Boolean.TRUE.equals(XposedHelpers.callMethod(manager, "isInDexDisplay"))) return false;
        Object info = XposedHelpers.getObjectField(manager, "startingInfo");
        if (info == null || Boolean.TRUE.equals(XposedHelpers.callMethod(info, "isDefaultHome"))
            || Boolean.TRUE.equals(XposedHelpers.callMethod(info, "getByKey"))) return false;
        Object end = XposedHelpers.callMethod(manager, "getEndTarget");
        if (end == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(end, "isHome")) || !isThirdPartyHome(manager)) return false;
        Object callback = XposedHelpers.getObjectField(manager, "stateCallbacks");
        synchronized (pipTransitions) { if (pipTransitions.get(manager) == callback && callback != null) return false; }
        Object state = XposedHelpers.getStaticObjectField(XposedHelpers.findClass("com.honeyspace.gesture.recentsanimation.GestureState", loader), "Companion");
        int finished = (Integer) XposedHelpers.callMethod(state, "getSTATE_RECENTS_ANIMATION_FINISHED");
        if (callback == null || Boolean.TRUE.equals(XposedHelpers.callMethod(callback, "hasState", finished))) return false;
        Object updater = XposedHelpers.getObjectField(manager, "leashUpdater");
        if (updater != null) {
            Object targets = XposedHelpers.callMethod(updater, "getTargets");
            Object running = XposedHelpers.callMethod(targets, "findTask", XposedHelpers.getIntField(manager, "taskId"));
            if (running != null && XposedHelpers.getBooleanField(running, "allowEnterPip")) {
                if (Boolean.TRUE.equals(XposedHelpers.callMethod(manager, "canPipTransition", running)))
                    return false;
            }
        }
        return true;
    }

    private boolean eligible(Object manager, Object appeared) {
        if (!eligibleCommittedHome(manager) || appeared == null) return false;
        for (int i=0; i<Array.getLength(appeared); i++) {
            Object target = Array.get(appeared, i);
            if (target == null) continue;
            SurfaceControl homeSurface = (SurfaceControl) XposedHelpers.getObjectField(target, "leash");
            if (homeSurface == null || !homeSurface.isValid()) continue;
            Object config = XposedHelpers.getObjectField(target, "windowConfiguration");
            int type = (Integer) XposedHelpers.callMethod(config, "getActivityType");
            if (type == 3) return false; // Original callback handles Recents first and returns.
            if (type != 2) continue;
            Object task = XposedHelpers.getObjectField(target, "taskInfo");
            if (task == null) return false;
            ComponentName top = (ComponentName) XposedHelpers.getObjectField(task, "topActivity");
            ComponentName home = thirdPartyHome(manager);
            return top != null && home != null && LauncherPolicy.matchesHome(home.getPackageName(), top.getPackageName());
        }
        return false;
    }

    private void log(String message) {
        if (diagnostics) logImportant(message);
    }
    private static void logImportant(String message) {
        String line = "OneUIHomeHandoff t=" + SystemClock.uptimeMillis() + " " + message;
        XposedBridge.log(line); Log.i("OneUIHomeHandoff", message);
    }
    private void failure(String where, Throwable error) {
        logImportant("ERROR " + where + ": " + error.getClass().getSimpleName());
    }
}
