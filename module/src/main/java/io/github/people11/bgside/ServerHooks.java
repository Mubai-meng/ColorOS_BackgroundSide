package io.github.people11.bgside;

import static io.github.people11.bgside.H.TAG;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.SurfaceControl;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** system_server side: every minimize to the bubble moves the task to its own Mirage keep-alive display. */
final class ServerHooks {
    private static final int INVALID_DISPLAY = -1;
    private static final float HANG_FPS = 10f;
    private static final long PENDING_TIMEOUT_MS = 10_000;
    private static final long RELEASE_DELAY_MS = 1_000;
    /** A bubble restore keeps animating and re-binding windows well after the task reparents. */
    private static final long RESTORE_RELEASE_DELAY_MS = 2_500;
    private static final int SF_SET_FRAME_RATE_OVERRIDE = 1039;

    private static final class Hang {
        volatile String pkg;
        volatile int uid = -1;
        volatile int display = INVALID_DISPLAY;
        volatile boolean ready;
        volatile boolean moved;
    }

    private static final Map<Integer, Hang> hung = new ConcurrentHashMap<>();
    /** Inside the stock bubble restore. */
    private static final Set<Integer> restoring = ConcurrentHashMap.newKeySet();

    private static Handler handler;
    private static ClassLoader loader;
    private static int failures;
    /** Resolved once: reached from hot callbacks (display config, every activity launch). */
    private static volatile Object cachedService;
    private static volatile Object cachedAtms;
    private static volatile Object cachedRoot;
    private static volatile Object cachedLock;
    private static volatile IBinder cachedSf;
    private static volatile boolean fpsOverrideRejected;
    /** uid -> number of tasks pinned at HANG_FPS. */
    private static final Map<Integer, Integer> fpsPins = new ConcurrentHashMap<>();
    /**
     * Resolved once, outside the WM lock: {@code forAllWindows} has two overloads with the same arity
     * (Consumer / ToBooleanFunction) so {@link H#method} would refuse it as ambiguous.
     */
    private static volatile Method forAllWindows;
    /** {@code OplusMirageWindowManagerService$8} is R8-numbered; re-resolving it per restore was wasted work. */
    private static volatile Constructor<?> syncMoveCtor;
    private static volatile Class<?> flexibleUtilsClass;
    private static volatile Class<?> fwmsClass;
    private static volatile Class<?> mirageClientClass;

    private interface Step {
        void run() throws Throwable;
    }

    /** Runs one hook in isolation, so a rename in a ROM update costs only that hook. */
    private static void step(String name, Step body) {
        try {
            body.run();
        } catch (Throwable t) {
            failures++;
            Log.e(TAG, "hook failed: " + name, t);
        }
    }

    static void install(ClassLoader cl) {
        if (handler != null) {
            Log.w(TAG, "install called twice, ignoring");
            return;
        }
        loader = cl;
        var thread = new HandlerThread("BgSide");
        thread.start();
        handler = new Handler(thread.getLooper());
        try {
            Class<?> toFloat = cl.loadClass("com.android.server.wm.ExitFlexibleTaskToFloatStrategy");
            Class<?> ftc = cl.loadClass("com.android.server.wm.FlexibleTaskController");
            Class<?> tfExt = cl.loadClass("com.android.server.wm.TaskFragmentExtImpl");
            Class<?> fhc = cl.loadClass("com.android.server.wm.FloatHandleController");
            Class<?> infoManager = cl.loadClass("com.android.server.wm.OplusFlexibleInfoManager");
            Class<?> openAnimSpec = cl.loadClass("com.android.server.wm.FlexibleFloatHandleAnimationSpec");
            Class<?> starter = cl.loadClass("com.android.server.wm.ActivityStarter");
            Class<?> supervisor = cl.loadClass("com.android.server.wm.ActivityTaskSupervisor");
            Class<?> tcn = cl.loadClass("com.android.server.wm.TaskChangeNotificationController");
            Class<?> keepAlive = cl.loadClass("com.android.server.wm.OplusMirageKeepAlivePolicy");
            Class<?> displayContent = cl.loadClass("com.android.server.wm.DisplayContent");
            Class<?> taskClass = cl.loadClass("com.android.server.wm.Task");

            // Every minimize path (menu, caption, gesture, auto-collapse) starts here.
            step("minimize entry", () -> H.hook(toFloat, "handleEvent", a -> {
                int id = H.get(H.get(a.getReference(0), "mTask"), "mTaskId");
                // A new minimize means the previous restore flow is over; drop any id left behind if a
                // hook muted itself before the after-callback could run.
                restoring.remove(id);
                Hang h = new Hang();
                Hang prev = hung.putIfAbsent(id, h);
                if (prev != null) {
                    // A task that is already hung cannot be minimized again, so this entry is a leftover
                    // from a task that died without notifyTaskRemoved. Left alone it would leak its display
                    // and make this task id un-hangable forever.
                    if (!prev.moved) return false;
                    Log.w(TAG, "dropping stale hang for task " + id + " on display " + prev.display);
                    hung.remove(id, prev);
                    releaseHang(prev);
                    if (hung.putIfAbsent(id, h) != null) return false;
                }
                Log.d(TAG, "minimizing task " + id);
                handler.post(() -> prepare(id, h, 0));
                handler.postDelayed(() -> {
                    if (hung.get(id) == h && !h.moved) unhang(id, "minimize never finished");
                }, PENDING_TIMEOUT_MS);
                return false;
            }, null));

            // Don't pause during the minimize animation.
            step("skip pause in recents anim", () -> H.hook(tfExt, "shouldSkipPauseInRecentsAnim", a -> {
                if (hung.isEmpty()) return false;
                Object tf = H.get(a.getReference(0), "mTaskFragment");
                // Class reference, not getSimpleName(): no String per float-window animation.
                Hang h = tf != null && tf.getClass() == taskClass
                        ? hung.get((int) H.get(tf, "mTaskId")) : null;
                if (h == null || h.moved) return false;
                a.setBoolean(H.RET, true);
                return true;
            }, null));

            // The stock exit hides the task behind home; move it to our display before visibility is recomputed.
            // Plain WM move: Mirage's would reset the flexible-window state (size, hidden caption).
            // ColorOS 17: the 4-arg overload only forwards to the 7-arg body, so hook the body to see every path once.
            step("hang on exit", () -> H.around(ftc, "exitFlexibleTask", 7, (a, original) -> {
                Object task = a.getReference(1);
                int id = H.get(task, "mTaskId");
                Hang h = hung.get(id);
                if (h == null || h.moved) {
                    original.call();
                    return;
                }
                if (!h.ready) {
                    // Minimize without animation: the display (~30 ms) isn't ready yet.
                    unhang(id, "display not ready in time");
                    original.call();
                    return;
                }
                h.pkg = H.call(task, "getBasePackageName");
                h.uid = H.get(task, "effectiveUid");
                h.moved = true;
                Log.i(TAG, "hanging task " + id + " (" + h.pkg + ") on display " + h.display);
                pinFps(h.uid);
                try {
                    withDeferredVisibility(() -> {
                        original.call();
                        Object root = rootWindowContainer();
                        H.method(root.getClass(), "moveRootTaskToDisplay", 3).invoke(root, id, h.display, false);
                    });
                } catch (Throwable t) {
                    // The move failed after the stock exit ran: revert, or this task would look hung
                    // while still sitting on the main screen.
                    h.moved = false;
                    unpinFps(h.uid);
                    throw t;
                }
            }));

            // Keep running with the screen off.
            step("keep alive with screen off", () -> H.hook(keepAlive, "shouldSleep", a -> {
                if (hung.isEmpty()) return false;
                Object info = H.get(a.getReference(0), "mDisplayInfo");
                if (!isOurDisplay(H.get(info, "displayId"))) return false;
                a.setBoolean(H.RET, false);
                return true;
            }, null));

            // Match the real screen's touch / color mode: Chrome recreates (and stops media) on that config change.
            // Every display, every configuration pass: leave before any reflection when nothing is hung.
            step("match screen config", () -> H.hook(displayContent, "computeScreenConfiguration", 1, null, a -> {
                if (hung.isEmpty()) return;
                if (!isOurDisplay(H.get(a.getReference(0), "mDisplayId"))) return;
                Configuration config = a.getReference(1);
                Configuration main = H.call(H.call(rootWindowContainer(), "getDefaultDisplay"), "getConfiguration");
                config.touchscreen = main.touchscreen;
                config.colorMode = main.colorMode;
            }));

            // Bubble tap: the stock restore moves tasks off VIRTUAL displays itself, then uses startActivityFromRecents.
            step("restore from bubble", () -> H.hook(fhc, "startActivityByFloatInfo", a -> {
                restoring.add(H.get(a.getReference(1), "taskId"));
                return false;
            }, a -> {
                int id = H.get(a.getReference(1), "taskId");
                restoring.remove(id);
                unhang(id, "restored from bubble", RESTORE_RELEASE_DELAY_MS);
            }));

            // The restore would cover the live app with its stale minimize-time snapshot until ~1 s after opening.
            step("skip stale snapshot", () -> H.hook(infoManager, "isSkipRecordSnapshot", a -> {
                if (hung.isEmpty()) return false;
                Hang h = hung.get((int) H.get(H.get(a.getReference(0), "mFlexibleTask"), "mTaskId"));
                if (h == null || !h.moved) return false;
                a.setBoolean(H.RET, true);
                return true;
            }, null));

            // The open animation's first frame leaves the leash uncropped, so the live app peeks out below the bubble.
            step("crop first open frame", () -> H.hook(openAnimSpec, "defaultCallAnimationStart", a -> {
                if (restoring.isEmpty()) return false;
                Object spec = a.getReference(0);
                if (!restoring.contains((int) H.get(H.get(spec, "mAnimTask"), "mTaskId"))) return false;
                SurfaceControl.Transaction t = a.getReference(1);
                t.setCrop(H.<SurfaceControl>get(spec, "mTaskAnimLeash"), new Rect(0, 0, 1, 1));
                return false;
            }, null));

            // Launch from home / recents: move back first, else the transition sees a display change and won't animate.
            step("launch from home", () -> H.hook(starter, "execute", 0, a -> {
                if (hung.isEmpty()) return false;
                Intent intent = H.get(H.get(a.getReference(0), "mRequest"), "intent");
                if (intent == null || intent.getComponent() == null || !Intent.ACTION_MAIN.equals(intent.getAction())
                        || !intent.hasCategory(Intent.CATEGORY_LAUNCHER)) return false;
                String pkg = intent.getComponent().getPackageName();
                // shortcut: matches by package only, so a clone app's launch also ends the hang.
                for (var e : hung.entrySet()) {
                    if (e.getValue().moved && pkg.equals(e.getValue().pkg)) leaveHang(e.getKey(), "launched from home");
                }
                return false;
            }, null));

            step("launch from recents", () -> H.hook(supervisor, "startActivityFromRecents", a -> {
                if (hung.isEmpty()) return false;
                int id = a.getInt(3);
                Hang h = hung.get(id);
                if (h != null && h.moved && !restoring.contains(id)) leaveHang(id, "launched from recents");
                return false;
            }, null));

            // Bubble dismissed: every removal path (menu, swipe, recents) funnels through here.
            // ColorOS 17: dispatchViewRemoved is gone (inlined into a numbered lambda), this is the stable funnel.
            step("bubble removed", () -> H.hook(fhc, "removeFloatHandleInner", 3, a -> {
                int id = a.getInt(1);
                if (restoring.contains(id)) return false; // the stock restore unhangs itself
                Hang h = hung.get(id);
                if (!unhang(id, "bubble removed")) return false;
                handler.post(() -> {
                    if (isTaskOnDisplay(id, h.display)) moveBack(id);
                });
                return false;
            }, null));

            step("task removed", () -> H.hook(tcn, "notifyTaskRemoved", a -> {
                if (hung.isEmpty()) return false;
                unhang(a.getInt(1), "task removed");
                return false;
            }, null));
        } catch (Throwable t) {
            Log.e(TAG, "server hooks aborted: class lookup failed", t);
            return;
        }
        Log.i(TAG, failures == 0
                ? "server hooks installed"
                : "server hooks installed with " + failures + " failure(s), see above");
    }

    /** On {@link #handler}: create the display and wait until Mirage and WM know it. */
    private static void prepare(int taskId, Hang h, int attempt) {
        if (hung.get(taskId) != h) return; // cancelled meanwhile
        try {
            if (h.display == INVALID_DISPLAY) {
                h.display = createDisplay();
                if (h.display == INVALID_DISPLAY) {
                    unhang(taskId, "create virtual display failed");
                    return;
                }
            }
            int d = h.display;
            if ((boolean) H.call(mirageService(), "isMirageDisplayId", d) && ensureDisplayContent(d)) {
                h.ready = true;
            } else if (attempt < 40) {
                handler.postDelayed(() -> prepare(taskId, h, attempt + 1), 50);
            } else {
                unhang(taskId, "display " + d + " never became ready");
            }
        } catch (Throwable t) {
            Log.e(TAG, "prepare " + taskId + " failed", t);
            unhang(taskId, "prepare failed");
        }
    }

    private static void leaveHang(int taskId, String why) {
        moveBack(taskId);
        unhang(taskId, why);
    }

    /** @return true if the task was hung. */
    private static boolean unhang(int taskId, String why) {
        return unhang(taskId, why, RELEASE_DELAY_MS);
    }

    /** As {@link #unhang(int, String)}, keeping the virtual display alive {@code releaseDelayMs} longer. */
    private static boolean unhang(int taskId, String why, long releaseDelayMs) {
        Hang h = hung.remove(taskId);
        if (h == null) return false;
        restoring.remove(taskId);
        Log.i(TAG, "unhang task " + taskId + ": " + why);
        releaseHang(h, releaseDelayMs);
        // ColorOS 17 reparents the task but not its windows: rebind once early and once after the animation.
        handler.postDelayed(() -> repairWindowDisplays(taskId), 350);
        handler.postDelayed(() -> repairWindowDisplays(taskId), releaseDelayMs + 500);
        return true;
    }

    /**
     * Windows that came back from the virtual display keep its id in {@code WindowState.mInputWindowHandle}:
     * ColorOS 17's restore moves the task and the app window (which re-lays out and fixes itself) but not the
     * WMS-owned caption, so InputDispatcher logs "window ... in display 0, but it should belong to display
     * 10000" and drops every tap on the caption - the "fullscreen button does nothing" symptom. Going through
     * the normal {@code onDisplayChanged} path repairs the input handle and the insets/IME targets with it.
     */
    private static void repairWindowDisplays(int taskId) {
        // Minimized again before a queued repair ran: its windows now belong to a virtual display on
        // purpose, so rebinding them would be wrong.
        if (hung.containsKey(taskId)) return;
        try {
            Method forAll = forAllWindowsMethod();
            Object root = rootWindowContainer();
            synchronized (globalLock()) {
                Object main = H.call(root, "getDefaultDisplay");
                if (main == null) return;
                ArrayList<Object> windows = new ArrayList<>();
                Consumer<Object> sink = windows::add;
                forAll.invoke(main, sink, true);
                int fixed = 0;
                for (Object w : windows) {
                    Object handle = H.get(w, "mInputWindowHandle");
                    int hd = handle == null ? Display.DEFAULT_DISPLAY : (int) H.call(handle, "getDisplayId");
                    if ((int) H.call(w, "getDisplayId") == Display.DEFAULT_DISPLAY
                            && hd == Display.DEFAULT_DISPLAY) continue;
                    H.call(w, "onDisplayChanged", main);
                    fixed++;
                }
                if (fixed > 0) {
                    Log.i(TAG, "rebound " + fixed + " window(s) to the main display after task " + taskId);
                    H.method(root.getClass(), "ensureActivitiesVisible", 0).invoke(root);
                    // The flexible caption only receives touch while its task owns the focused window of
                    // the display, so a rebind has to be followed by one recompute. Both calls are full
                    // traversals and must stay off the common path, where nothing was rebound.
                    H.method(root.getClass(), "updateFocusedWindowLocked", 2).invoke(root, 0, true);
                }
                // Observation only: on ColorOS 17 the restore transition owns the focus, so a miss here is
                // expected and is what the log records. Do not act on it, the transition will finish.
                int focus = focusedTaskId(main);
                if (focus != taskId) {
                    Log.d(TAG, "task " + taskId + " does not own the focus after restore (focused task " + focus + ")");
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "window rebind for task " + taskId + " failed", t);
        }
    }

    /** Resolved once and outside the WM lock: a reflection lookup must not extend the lock hold. */
    private static Method forAllWindowsMethod() throws ReflectiveOperationException {
        Method m = forAllWindows;
        if (m == null) {
            m = loader.loadClass("com.android.server.wm.WindowContainer")
                    .getDeclaredMethod("forAllWindows", Consumer.class, boolean.class);
            m.setAccessible(true);
            forAllWindows = m;
        }
        return m;
    }

    /**
     * A task that the flexible-window code moved back to the main display stays in Mirage's per-display
     * task set, because only a stack removal prunes it. Releasing the display would then drag that task
     * through {@code moveStacksToDefaultDisplay}: snapshot, {@code makeInvisible()}, task surface hidden,
     * and no actual move since it is already on display 0. The restored float window ends up hidden and
     * unfocused, which silently kills every caption interaction. Prune those entries first.
     */
    private static void forgetTasksThatLeft(int displayId) {
        try {
            synchronized (globalLock()) {
                Object controller = H.get(mirageService(), "mWindowController");
                if (controller == null) return;
                Object display = H.call(controller, "getDevice", displayId);
                if (display == null) return;
                Object tasks = H.call(display, "getTasks");
                if (!(tasks instanceof Set)) return;
                int dropped = 0;
                for (Object id : new ArrayList<>((Set<?>) tasks)) {
                    if (isTaskOnDisplay((Integer) id, displayId)) continue;
                    H.call(display, "removeTask", id);
                    dropped++;
                }
                if (dropped > 0) Log.i(TAG, "display " + displayId + ": dropped " + dropped + " task(s) that already left");
            }
        } catch (Throwable t) {
            Log.e(TAG, "mirage task cleanup for display " + displayId + " failed", t);
        }
    }

    /** @return the task id owning the main display's focused window, or -1. */
    private static int focusedTaskId(Object mainDisplay) {
        try {
            Object window = H.call(H.call(mainDisplay, "getDisplayPolicy"), "getFocusedWindow");
            if (window == null) return -1;
            Object task = H.call(window, "getTask");
            return task == null ? -1 : (int) H.get(task, "mTaskId");
        } catch (Throwable t) {
            return -1;
        }
    }

    private static boolean isOurDisplay(int d) {
        if (d == INVALID_DISPLAY) return false;
        // Every config pass for the main display lands here; leave before allocating an iterator.
        if (d == Display.DEFAULT_DISPLAY) return false;
        for (Hang h : hung.values()) {
            if (h.display == d) return true;
        }
        return false;
    }

    private static boolean isTaskOnDisplay(int taskId, int d) {
        try {
            Object root = rootWindowContainer();
            synchronized (globalLock()) {
                Object task = H.method(root.getClass(), "anyTaskForId", 1).invoke(root, taskId);
                return task != null && (int) H.call(task, "getDisplayId") == d;
            }
        } catch (Throwable t) {
            Log.e(TAG, "display lookup for task " + taskId + " failed", t);
            return false;
        }
    }

    /** Drops the frame-rate pin a stale entry still holds, and its keep-alive display. */
    private static void releaseHang(Hang h) {
        releaseHang(h, RELEASE_DELAY_MS);
    }

    private static void releaseHang(Hang h, long releaseDelayMs) {
        if (h.moved) unpinFps(h.uid);
        int d = h.display;
        if (d == INVALID_DISPLAY || d == Display.DEFAULT_DISPLAY) return;
        handler.postDelayed(() -> {
            try {
                forgetTasksThatLeft(d);
                mirage("releaseVirtualDisplayDevice", d);
            } catch (Throwable t) {
                // A throw here would reach the main looper and take system_server down with it.
                Log.e(TAG, "release display " + d + " failed", t);
            }
        }, releaseDelayMs);
    }

    /** The override is per uid, so two hung tasks of the same app must not clobber each other. */
    private static void pinFps(int uid) {
        if (uid < 0) return;
        if (fpsPins.merge(uid, 1, Integer::sum) == 1) sendFps(uid, HANG_FPS);
    }

    private static void unpinFps(int uid) {
        if (uid < 0) return;
        Integer left = fpsPins.compute(uid, (k, v) -> v == null || v <= 1 ? null : v - 1);
        if (left == null) sendFps(uid, 0f);
    }

    /** SurfaceFlinger's per-uid override backdoor; unlike GameManager's it survives a forced rate (1035). 0 = clear. */
    private static void sendFps(int uid, float fps) {
        if (uid < 0) return;
        handler.post(() -> {
            Parcel data = Parcel.obtain();
            try {
                IBinder sf = cachedSf;
                // SurfaceFlinger restarts independently of system_server; a dead proxy would fail silently
                // forever, leaving the hang at full frame rate.
                if (sf == null || !sf.isBinderAlive()) {
                    sf = H.callStatic(Class.forName("android.os.ServiceManager"), "getService", "SurfaceFlinger");
                    cachedSf = sf;
                }
                data.writeInterfaceToken("android.ui.ISurfaceComposer");
                data.writeInt(uid);
                data.writeFloat(fps);
                if (!sf.transact(SF_SET_FRAME_RATE_OVERRIDE, data, null, 0) && !fpsOverrideRejected) {
                    fpsOverrideRejected = true;
                    Log.w(TAG, "SurfaceFlinger rejected frame rate override (code "
                            + SF_SET_FRAME_RATE_OVERRIDE + "); hang will run at full frame rate");
                }
            } catch (Throwable t) {
                // Report once: a per-hang stack trace would be a log flood with zero diagnostic value.
                if (!fpsOverrideRejected) {
                    fpsOverrideRejected = true;
                    Log.e(TAG, "frame rate override " + fps + " for uid " + uid
                            + " failed; hang will run at full frame rate", t);
                }
            } finally {
                data.recycle();
            }
        });
    }

    private static int createDisplay() throws ReflectiveOperationException {
        DisplayMetrics dm = new DisplayMetrics();
        systemContext().getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(dm);

        Object o = H.callStatic(loader.loadClass("com.oplus.miragewindow.OplusMirageOptions"), "makeAppKeepModeOptions");
        H.call(o, "setDensityDpi", dm.densityDpi);
        H.call(o, "setXDpi", dm.xdpi);
        H.call(o, "setYDpi", dm.ydpi);
        H.call(o, "setDisplayWidth", Math.min(dm.widthPixels, dm.heightPixels));
        H.call(o, "setDisplayHeight", Math.max(dm.widthPixels, dm.heightPixels));
        Bundle b = H.call(o, "toBundle");
        // Own display group: stays on when the main screen turns off.
        b.putBoolean("mirage:ownDisplayGroup", true);
        return mirage("createVirtualDisplayDevice", b);
    }

    /** WM doesn't create a DisplayContent for keep-alive displays by itself. */
    private static boolean ensureDisplayContent(int d) throws ReflectiveOperationException {
        Object wms = H.get(mirageService(), "mWms");
        synchronized ((Object) H.get(wms, "mGlobalLock")) {
            return H.call(H.get(wms, "mRoot"), "getDisplayContentOrCreate", d) != null;
        }
    }

    /** Runs {@code body} with visibility updates deferred, so a reparented task is never briefly invisible (stopped). */
    private static void withDeferredVisibility(H.Invoker body) throws Throwable {
        Object service = atms();
        Object supervisor = H.get(service, "mTaskSupervisor");
        Object root = H.get(service, "mRootWindowContainer");
        synchronized (globalLock()) {
            H.call(supervisor, "beginDeferResume");
            H.call(supervisor, "setDeferRootVisibilityUpdate", true);
            try {
                body.call();
            } finally {
                H.call(supervisor, "setDeferRootVisibilityUpdate", false);
                H.call(supervisor, "endDeferResume");
                // Keep focus (keys, IME) on the real screen.
                H.method(root.getClass(), "positionChildAt", 3)
                        .invoke(root, Integer.MAX_VALUE, H.call(root, "getDefaultDisplay"), true);
                H.method(root.getClass(), "updateFocusedWindowLocked", 2).invoke(root, 0, true);
                H.method(root.getClass(), "ensureActivitiesVisible", 0).invoke(root);
                H.method(root.getClass(), "resumeFocusedTasksTopActivities", 0).invoke(root);
            }
        }
    }

    /** Back to display 0 as a plain hidden full-screen background task (what Mirage's move-in used to leave). */
    private static void moveBack(int taskId) {
        Class<?> utils;
        Class<?> fwms;
        try {
            utils = flexibleUtilsClass;
            if (utils == null) {
                utils = loader.loadClass("com.android.server.wm.FlexibleWindowUtils");
                flexibleUtilsClass = utils;
            }
            fwms = fwmsClass;
            if (fwms == null) {
                fwms = loader.loadClass("com.android.server.wm.FlexibleWindowManagerService");
                fwmsClass = fwms;
            }
        } catch (Throwable t) {
            Log.e(TAG, "flexible utils lookup failed, moving without the reset", t);
            moveSync(taskId, Display.DEFAULT_DISPLAY);
            return;
        }
        try {
            Object root = rootWindowContainer();
            synchronized (globalLock()) {
                Object task = H.method(root.getClass(), "anyTaskForId", 1).invoke(root, taskId);
                // 4 overloads (ActivityOptions/WindowContainer/Task/TaskInfo): bind by the task's own type.
                if (task != null && (boolean) utils.getMethod("isFlexibleTaskAndHasCaption", task.getClass()).invoke(null, task)) {
                    Object fwmsService = H.callStatic(fwms, "getInstance", (Object) null);
                    H.call(H.call(fwmsService, "getFlexibleTaskController"), "resetFlexibleTaskToFullScreen", task);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "flexible reset for task " + taskId + " failed", t);
        }
        moveSync(taskId, Display.DEFAULT_DISPLAY);
    }

    /** Mirage's move, run synchronously; moving to display 0 it hides the task first. */
    private static void moveSync(int taskId, int displayId) {
        try {
            Object svc = mirageService();
            // shortcut: R8-numbered class on CPH2723 16.0.2; if a ROM update renames it, the async fallback is used.
            Constructor<?> c = syncMoveCtor;
            if (c == null) {
                c = loader.loadClass("com.android.server.wm.OplusMirageWindowManagerService$8")
                        .getDeclaredConstructor(svc.getClass(), int.class, int.class, boolean.class);
                c.setAccessible(true);
                syncMoveCtor = c;
            }
            ((Runnable) c.newInstance(svc, taskId, displayId, false)).run();
        } catch (Throwable t) {
            Log.w(TAG, "sync move unavailable, falling back to async", t);
            try {
                mirage("moveTaskToDisplay", taskId, displayId, false);
            } catch (Throwable t2) {
                Log.e(TAG, "move task " + taskId + " to display " + displayId + " failed", t2);
            }
        }
    }

    private static Object mirageService() throws ReflectiveOperationException {
        Object s = cachedService;
        if (s == null) {
            s = H.callStatic(loader.loadClass("com.android.server.wm.OplusMirageWindowManagerService"), "getInstance");
            if (s != null) cachedService = s;
        }
        return s;
    }

    private static Object atms() throws ReflectiveOperationException {
        Object a = cachedAtms;
        if (a == null) a = cachedAtms = H.get(mirageService(), "mAtms");
        return a;
    }

    private static Object rootWindowContainer() throws ReflectiveOperationException {
        Object r = cachedRoot;
        if (r == null) r = cachedRoot = H.get(atms(), "mRootWindowContainer");
        return r;
    }

    private static Object globalLock() throws ReflectiveOperationException {
        Object l = cachedLock;
        if (l == null) l = cachedLock = H.get(atms(), "mGlobalLock");
        return l;
    }

    /** Public OplusMirageWindowManager client API. */
    private static <T> T mirage(String method, Object... args) {
        try {
            Class<?> c = mirageClientClass;
            if (c == null) {
                c = loader.loadClass("com.oplus.miragewindow.OplusMirageWindowManager");
                mirageClientClass = c;
            }
            Object m = H.callStatic(c, "getInstance");
            return H.call(m, method, args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("mirage " + method, e);
        }
    }

    private static Context systemContext() throws ReflectiveOperationException {
        Object at = H.callStatic(Class.forName("android.app.ActivityThread"), "currentActivityThread");
        return H.call(at, "getSystemContext");
    }

    private ServerHooks() {
    }
}
