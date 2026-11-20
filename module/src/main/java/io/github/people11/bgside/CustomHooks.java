package io.github.people11.bgside;

import static io.github.people11.bgside.H.TAG;

import android.os.Handler;
import android.util.Log;

/** Personal tweaks to the stock flexible (floating) window behavior. */
final class CustomHooks {
    private static Class<?> adapter;

    static void install(ClassLoader cl) {
        try {
            // Mini (taskState 2) pins the border; use the normal rule: shown only while a corner is dragged.
            adapter = cl.loadClass("com.android.server.wm.FlexibleTaskIndicatorAdapter");
            H.hook(adapter, "shouldAlwaysShowIndicatorState", a -> {
                a.setBoolean(H.RET, false);
                return true;
            }, null);
            H.hook(adapter, "shouldShowIndicatorState", a -> {
                Object self = a.getReference(0);
                if (a.getReference(1) != H.call(H.get(self, "mIndicatorManager"), "getBorderIndicator")) return false;
                Object d = dragState(self);
                a.setBoolean(H.RET, d != null && dragging(d)
                        && ((boolean) H.call(d, "inDraggingLeftCornerArea") || (boolean) H.call(d, "inDraggingRightCornerArea")
                        || (boolean) H.call(d, "inDraggingBottomCornerArea")));
                return true;
            }, null);
            // Mini: hide the corners with the border, not after 5 s. The stock code shows them without scheduling the
            // timeout in places (resize into mini, cancelled animations), so also hide after every visibility refresh.
            Class<?> indicators = cl.loadClass("com.android.server.wm.FlexibleTaskIndicatorWindowManager");
            H.hook(indicators, "scheduleIndicatorTimeout", null, a -> hideCornersInMini(a.getReference(0)));
            H.hook(indicators, "refreshAllIndicatorsVisibility", null, a -> hideCornersInMini(a.getReference(0)));

            // No shrink to mini on going home: gesture (hideZoomWindow 12), home / recents keys (onRecentClicked).
            Class<?> ftc = cl.loadClass("com.android.server.wm.FlexibleTaskController");
            H.hook(ftc, "hideZoomWindow", a -> a.getInt(1) == 12, null);
            H.hook(ftc, "switchStateAll", 2, a -> "onRecentClicked".equals(a.getReference(2)), null);
        } catch (Throwable t) {
            Log.e(TAG, "custom install failed", t);
        }
    }

    /** Runs the stock timeout hide now; it is a no-op when no corner is shown. */
    private static void hideCornersInMini(Object manager) throws ReflectiveOperationException {
        Object ad = H.get(manager, "mAdapter");
        if (!adapter.isInstance(ad)) return;
        Object info = H.call(H.get(ad, "mFlexibleTaskController"), "getFlexibleInfoManager", (Object) H.get(ad, "mObserverTask"));
        if ((int) H.call(info, "getTaskState") != 2) return;
        Object d = dragState(ad);
        if (d != null && dragging(d)) return;
        Handler handler = H.get(manager, "mHandler");
        Runnable timeout = H.get(manager, "mTimeOutRunnable");
        handler.removeCallbacks(timeout);
        handler.post(timeout);
    }

    private static Object dragState(Object adapter) throws ReflectiveOperationException {
        Object ext = H.call(H.call(H.get(adapter, "mObserverTask"), "getWrapper"), "getExtImpl");
        return H.call(ext, "getFlexibleTaskDragState");
    }

    private static boolean dragging(Object d) throws ReflectiveOperationException {
        return (boolean) H.call(d, "inDragging") || (boolean) H.call(d, "inDragStart");
    }

    private CustomHooks() {
    }
}
