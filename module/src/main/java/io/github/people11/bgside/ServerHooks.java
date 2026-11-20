package io.github.people11.bgside;

import static io.github.people11.bgside.H.TAG;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;

import java.lang.reflect.Constructor;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * system_server side.
 * <ol>
 * <li>Every minimize to the edge bubble hangs the task: a private Mirage keep-alive display is prepared for it, one
 *     per task so hung apps do not occlude each other.</li>
 * <li>The minimize animation does not pause the task, and where the stock minimize would push it behind home
 *     (invisible, stopped) it is moved to its display instead, in one deferred-visibility section, so it never stops.
 *     While hung the display does not sleep, looks like the real screen to the app, and the app runs at
 *     {@link #HANG_FPS}.</li>
 * <li>Leaving: tapping the bubble runs the stock restore (which already handles tasks on VIRTUAL displays); a launch
 *     from home / recents first moves the task back to display 0 so the stock launch animation plays; dismissing the
 *     bubble or removing the task just ends the hang.</li>
 * </ol>
 */
final class ServerHooks {
    private static final int INVALID_DISPLAY = -1;
    /** Frame rate cap for hung apps: keeps running, saves power. */
    private static final float HANG_FPS = 10f;
    /** A minimize that never completes (e.g. interrupted) is dropped after this. */
    private static final long PENDING_TIMEOUT_MS = 10_000;
    /** Lets a pending move back to display 0 run before the display goes away. */
    private static final long RELEASE_DELAY_MS = 1_000;
    /** SurfaceFlinger debug transaction: per-uid frame rate override (highest priority). */
    private static final int SF_SET_FRAME_RATE_OVERRIDE = 1039;

    private static final class Hang {
        volatile String pkg;
        volatile int uid = -1;
        volatile int display = INVALID_DISPLAY;
        /** Display exists in Mirage and WM. */
        volatile boolean ready;
        /** Task moved to our display. */
        volatile boolean moved;
    }

    /** taskId -> state, from the minimize until unhang. */
    private static final Map<Integer, Hang> hung = new ConcurrentHashMap<>();
    /** Tasks inside the stock bubble restore. */
    private static final Set<Integer> restoring = ConcurrentHashMap.newKeySet();

    private static Handler handler;
    private static ClassLoader loader;

    static void install(ClassLoader cl) {
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
            Class<?> starter = cl.loadClass("com.android.server.wm.ActivityStarter");
            Class<?> supervisor = cl.loadClass("com.android.server.wm.ActivityTaskSupervisor");
            Class<?> tcn = cl.loadClass("com.android.server.wm.TaskChangeNotificationController");
            Class<?> keepAlive = cl.loadClass("com.android.server.wm.OplusMirageKeepAlivePolicy");
            Class<?> displayContent = cl.loadClass("com.android.server.wm.DisplayContent");

            // --- Entering ---

            // Every minimize (menu, caption, gesture, auto-collapse when another window opens) starts here.
            H.hook(toFloat, "handleEvent", a -> {
                int id = H.get(H.get(a.getReference(0), "mTask"), "mTaskId");
                Hang h = new Hang();
                if (hung.putIfAbsent(id, h) != null) return false;
                Log.d(TAG, "minimizing task " + id);
                handler.post(() -> prepare(id, h, 0));
                handler.postDelayed(() -> {
                    if (hung.get(id) == h && !h.moved) unhang(id, "minimize never finished");
                }, PENDING_TIMEOUT_MS);
                return false;
            }, null);

            // The minimize animation start pauses the task ("pause in recents anim"); not for a task being hung.
            H.hook(tfExt, "shouldSkipPauseInRecentsAnim", a -> {
                if (hung.isEmpty()) return false;
                Object tf = H.get(a.getReference(0), "mTaskFragment");
                // shortcut: only handles leaf fragments that are Tasks (minimized flexible tasks are).
                Hang h = tf != null && tf.getClass().getSimpleName().equals("Task")
                        ? hung.get((int) H.get(tf, "mTaskId")) : null;
                if (h == null || h.moved) return false;
                a.setBoolean(H.RET, true);
                return true;
            }, null);

            // The minimize ends by pushing the task behind home. Let that stock exit run (the bubble and its restore
            // rely on the state it leaves), but move the task to our display in the same deferred-visibility section,
            // so the one visibility pass at the end sees it visible on its own display.
            H.around(ftc, "exitFlexibleTask", (a, original) -> {
                Object task = a.getReference(1);
                int id = H.get(task, "mTaskId");
                Hang h = hung.get(id);
                if (h == null || h.moved) {
                    original.call();
                    return;
                }
                if (!h.ready) {
                    // Only without a minimize animation (the display takes ~30 ms, the animation ~800 ms): plain minimize.
                    unhang(id, "display not ready in time");
                    original.call();
                    return;
                }
                h.pkg = H.call(task, "getBasePackageName");
                h.uid = H.get(task, "effectiveUid");
                h.moved = true;
                Log.i(TAG, "hanging task " + id + " (" + h.pkg + ") on display " + h.display);
                setFps(h.uid, HANG_FPS);
                withDeferredVisibility(() -> {
                    original.call();
                    moveSync(id, h.display);
                });
            });

            // --- While hung ---

            // KeepAlive displays normally sleep with the main screen; ours must not, so apps keep running.
            H.hook(keepAlive, "shouldSleep", a -> {
                Object info = H.get(a.getReference(0), "mDisplayInfo");
                if (!isOurDisplay(H.get(info, "displayId"))) return false;
                a.setBoolean(H.RET, false);
                return true;
            }, null);

            // Make our displays look like the real screen: a touch / color-mode difference is a config change some
            // apps (Chrome) answer by recreating the activity, which stops their media.
            H.hook(displayContent, "computeScreenConfiguration", 1, null, a -> {
                if (!isOurDisplay(H.get(a.getReference(0), "mDisplayId"))) return;
                Configuration config = a.getReference(1);
                Configuration main = H.call(H.call(rootWindowContainer(), "getDefaultDisplay"), "getConfiguration");
                config.touchscreen = main.touchscreen;
                config.colorMode = main.colorMode;
            });

            // --- Leaving ---

            // Bubble tap: the stock restore itself moves a task on a VIRTUAL display back to display 0
            // (moveRootTaskToDisplay, surface hidden until the open animation), then starts it via
            // startActivityFromRecents, which must not be taken for a launch from recents (see below).
            H.hook(fhc, "startActivityByFloatInfo", a -> {
                restoring.add(H.get(a.getReference(1), "taskId"));
                return false;
            }, a -> {
                int id = H.get(a.getReference(1), "taskId");
                restoring.remove(id);
                unhang(id, "restored from bubble");
            });

            // The open animation covers the task with its snapshot (taken at minimize: stale, low-res) until well after
            // the animation ends. A hung task kept running and draws a current frame at once, so skip the snapshot.
            H.hook(infoManager, "isSkipRecordSnapshot", a -> {
                if (hung.isEmpty()) return false;
                Hang h = hung.get((int) H.get(H.get(a.getReference(0), "mFlexibleTask"), "mTaskId"));
                if (h == null || !h.moved) return false;
                a.setBoolean(H.RET, true);
                return true;
            }, null);

            // Launch from home / recents: move the task back to display 0 before the start (and its transition)
            // begins, so it is an ordinary background task and the stock launch animation plays. Otherwise the
            // KeepAlive policy moves it during the start, leaving the transition with a display change.
            H.hook(starter, "execute", 0, a -> {
                if (hung.isEmpty()) return false; // hot path: skip the reflection
                Intent intent = H.get(H.get(a.getReference(0), "mRequest"), "intent");
                if (intent == null || intent.getComponent() == null || !Intent.ACTION_MAIN.equals(intent.getAction())
                        || !intent.hasCategory(Intent.CATEGORY_LAUNCHER)) return false;
                String pkg = intent.getComponent().getPackageName();
                // shortcut: matches by package only (no user id); launching a clone app also ends the hang.
                for (var e : hung.entrySet()) {
                    if (e.getValue().moved && pkg.equals(e.getValue().pkg)) leaveHang(e.getKey(), "launched from home");
                }
                return false;
            }, null);

            H.hook(supervisor, "startActivityFromRecents", a -> {
                int id = a.getInt(3);
                Hang h = hung.get(id);
                if (h != null && h.moved && !restoring.contains(id)) leaveHang(id, "launched from recents");
                return false;
            }, null);

            // Bubble dismissed: park the task back on display 0 (if nothing else moved it already).
            H.hook(fhc, "dispatchViewRemoved", null, a -> {
                int id = H.get(a.getReference(2), "taskId");
                Hang h = hung.get(id);
                if (!unhang(id, "bubble removed")) return;
                handler.post(() -> {
                    if (isTaskOnDisplay(id, h.display)) moveSync(id, Display.DEFAULT_DISPLAY);
                });
            });

            H.hook(tcn, "notifyTaskRemoved", a -> {
                unhang(a.getInt(1), "task removed");
                return false;
            }, null);

            Log.i(TAG, "server hooks installed");
        } catch (Throwable t) {
            Log.e(TAG, "server hooks failed", t);
        }
    }

    /** Runs on {@link #handler}: create the task's display and wait until Mirage and WM know it. */
    private static void prepare(int taskId, Hang h, int attempt) {
        if (hung.get(taskId) != h) return; // cancelled meanwhile
        try {
            if (h.display == INVALID_DISPLAY) h.display = createDisplay();
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

    /** Move a hung task back to display 0 (hidden, behind home: like a stock minimized task) and stop hanging. */
    private static void leaveHang(int taskId, String why) {
        moveSync(taskId, Display.DEFAULT_DISPLAY);
        unhang(taskId, why);
    }

    /** @return true if the task was hung. Restores its frame rate and releases its display. */
    private static boolean unhang(int taskId, String why) {
        Hang h = hung.remove(taskId);
        if (h == null) return false;
        Log.i(TAG, "unhang task " + taskId + ": " + why);
        if (h.moved) setFps(h.uid, 0f);
        int d = h.display;
        if (d != INVALID_DISPLAY) handler.postDelayed(() -> mirage("releaseVirtualDisplayDevice", d), RELEASE_DELAY_MS);
        return true;
    }

    private static boolean isOurDisplay(int d) {
        if (d == INVALID_DISPLAY) return false;
        for (Hang h : hung.values()) {
            if (h.display == d) return true;
        }
        return false;
    }

    private static boolean isTaskOnDisplay(int taskId, int d) {
        try {
            Object root = rootWindowContainer();
            synchronized ((Object) H.get(H.get(mirageService(), "mAtms"), "mGlobalLock")) {
                Object task = H.method(root.getClass(), "anyTaskForId", 1).invoke(root, taskId);
                return task != null && (int) H.call(task, "getDisplayId") == d;
            }
        } catch (ReflectiveOperationException e) {
            Log.e(TAG, "display lookup for task " + taskId + " failed", e);
            return false;
        }
    }

    /**
     * Via SurfaceFlinger's per-uid frame rate override backdoor: unlike the GameManager override it has the highest
     * priority, so it also applies when a refresh rate is forced (transaction 1035). fps 0 = clear.
     */
    private static void setFps(int uid, float fps) {
        if (uid < 0) return;
        handler.post(() -> {
            Parcel data = Parcel.obtain();
            try {
                IBinder sf = H.callStatic(Class.forName("android.os.ServiceManager"), "getService", "SurfaceFlinger");
                data.writeInterfaceToken("android.ui.ISurfaceComposer");
                data.writeInt(uid);
                data.writeFloat(fps);
                sf.transact(SF_SET_FRAME_RATE_OVERRIDE, data, null, 0);
            } catch (Throwable t) {
                Log.e(TAG, "frame rate override " + fps + " for uid " + uid + " failed", t);
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
        // Own display group: the display keeps its power state when the main screen turns off.
        b.putBoolean("mirage:ownDisplayGroup", true);
        return mirage("createVirtualDisplayDevice", b);
    }

    /** WM does not create a DisplayContent for Mirage keep-alive displays by itself; moving a task needs one. */
    private static boolean ensureDisplayContent(int d) throws ReflectiveOperationException {
        Object wms = H.get(mirageService(), "mWms");
        synchronized ((Object) H.get(wms, "mGlobalLock")) {
            return H.call(H.get(wms, "mRoot"), "getDisplayContentOrCreate", d) != null;
        }
    }

    /**
     * Run {@code body} under the WM lock with root visibility updates and resumes deferred, then do one visibility +
     * resume pass. Reparenting removes the task from its old display first; a pass in between would make it
     * invisible (stopped) for a moment. Safe with the WM lock already held (monitors are re-entrant).
     */
    private static void withDeferredVisibility(H.Invoker body) throws Throwable {
        Object atms = H.get(mirageService(), "mAtms");
        Object supervisor = H.get(atms, "mTaskSupervisor");
        Object root = H.get(atms, "mRootWindowContainer");
        synchronized ((Object) H.get(atms, "mGlobalLock")) {
            H.call(supervisor, "beginDeferResume");
            H.call(supervisor, "setDeferRootVisibilityUpdate", true);
            try {
                body.call();
            } finally {
                H.call(supervisor, "setDeferRootVisibilityUpdate", false);
                H.call(supervisor, "endDeferResume");
                // New displays sit above display 0 and take the global focus: keys/IME must stay on the real screen.
                H.method(root.getClass(), "positionChildAt", 3)
                        .invoke(root, Integer.MAX_VALUE, H.call(root, "getDefaultDisplay"), true);
                H.method(root.getClass(), "updateFocusedWindowLocked", 2).invoke(root, 0, true);
                H.method(root.getClass(), "ensureActivitiesVisible", 0).invoke(root);
                H.method(root.getClass(), "resumeFocusedTasksTopActivities", 0).invoke(root);
            }
        }
    }

    /**
     * Mirage's own move runnable, run on the calling thread (onTop=false: the hidden display must not take focus).
     * It resets flexible-window state for the move and, when moving to display 0, hides the task first.
     */
    private static void moveSync(int taskId, int displayId) {
        try {
            Object svc = mirageService();
            // shortcut: R8-numbered anonymous class on CPH2723 16.0.2; a ROM update may rename it (async fallback).
            Constructor<?> c = loader.loadClass("com.android.server.wm.OplusMirageWindowManagerService$8")
                    .getDeclaredConstructor(svc.getClass(), int.class, int.class, boolean.class);
            c.setAccessible(true);
            ((Runnable) c.newInstance(svc, taskId, displayId, false)).run();
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "sync move unavailable, falling back to async", e);
            mirage("moveTaskToDisplay", taskId, displayId, false);
        }
    }

    private static Object rootWindowContainer() throws ReflectiveOperationException {
        return H.get(H.get(mirageService(), "mAtms"), "mRootWindowContainer");
    }

    private static Object mirageService() throws ReflectiveOperationException {
        return H.callStatic(loader.loadClass("com.android.server.wm.OplusMirageWindowManagerService"), "getInstance");
    }

    /** Calls the public OplusMirageWindowManager client API (binder to ourselves, runs as system uid). */
    private static <T> T mirage(String method, Object... args) {
        try {
            Object m = H.callStatic(loader.loadClass("com.oplus.miragewindow.OplusMirageWindowManager"), "getInstance");
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
