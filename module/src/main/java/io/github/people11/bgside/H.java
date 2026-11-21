package io.github.people11.bgside;

import static com.v7878.unsafe.invoke.EmulatedStackFrame.RETURN_VALUE_IDX;

import android.util.Log;

import com.v7878.unsafe.Reflection;
import com.v7878.unsafe.invoke.EmulatedStackFrame.StackFrameAccessor;
import com.v7878.unsafe.invoke.Transformers;
import com.v7878.vmtools.Hooks;
import com.v7878.vmtools.Hooks.EntryPointType;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thin helpers over AndroidVMTools + hidden-API reflection. Arg index 0 is `this` for instance methods.
 *
 * <p>Members are resolved once and cached per class: these calls happen inside system_server hot paths
 * (display configuration, activity launch, recents animation), where re-walking the class hierarchy and
 * re-running {@code setAccessible} on every call is pure overhead. A member that keeps throwing gets
 * muted, so a renamed field cannot turn into a per-frame log flood.
 */
final class H {
    static final String TAG = "BgSide";
    static final int RET = RETURN_VALUE_IDX;
    /** Callback errors tolerated before a hook is muted. */
    private static final int MAX_HOOK_ERRORS = 5;

    private static final Object MISSING = new Object();

    /** One cache per class: a hit costs two map lookups and allocates nothing. */
    private static final class Members {
        final Map<String, Object> fields = new ConcurrentHashMap<>();
        /** name -> (argc -> Method). Keyed by the literal name, so nothing is built per call. */
        final Map<String, Map<Integer, Object>> methods = new ConcurrentHashMap<>();
    }

    private static final Map<Class<?>, Members> MEMBERS = new ConcurrentHashMap<>();

    interface Before {
        /** @return true to skip the original method */
        boolean run(StackFrameAccessor a) throws Throwable;
    }

    interface After {
        void run(StackFrameAccessor a) throws Throwable;
    }

    /** Hook the method found by {@link #method(Class, String)}. Errors in callbacks never break the original call. */
    static void hook(Class<?> c, String name, Before before, After after) {
        hook(c, name, -1, before, after);
    }

    /** As {@link #hook(Class, String, Before, After)}, for the overload with {@code argc} parameters. */
    static void hook(Class<?> c, String name, int argc, Before before, After after) {
        Method m = method(c, name, argc);
        int[] errors = {0};
        Hooks.hook(m, EntryPointType.CURRENT, (original, frame) -> {
            var a = frame.accessor();
            boolean skip = false;
            boolean live = errors[0] < MAX_HOOK_ERRORS;
            if (before != null && live) {
                try {
                    skip = before.run(a);
                } catch (Throwable t) {
                    errors[0] = fail(m, errors[0], t);
                }
            }
            try {
                if (!skip) Transformers.invokeExact(original, frame);
            } finally {
                if (after != null && errors[0] < MAX_HOOK_ERRORS) {
                    try {
                        after.run(a);
                    } catch (Throwable t) {
                        errors[0] = fail(m, errors[0], t);
                    }
                }
            }
        }, EntryPointType.DIRECT);
        Log.d(TAG, "hooked " + m);
    }

    interface Invoker {
        void call() throws Throwable;
    }

    interface Around {
        /** Must call {@code original} exactly once (it may do so inside its own locking). */
        void run(StackFrameAccessor a, Invoker original) throws Throwable;
    }

    /** Like {@link #hook}, but the callback decides when the original runs (still runs if the callback fails first). */
    static void around(Class<?> c, String name, Around around) {
        around(c, name, -1, around);
    }

    /** As {@link #around(Class, String, Around)}, for the overload with {@code argc} parameters. */
    static void around(Class<?> c, String name, int argc, Around around) {
        Method m = method(c, name, argc);
        int[] errors = {0};
        Hooks.hook(m, EntryPointType.CURRENT, (original, frame) -> {
            boolean[] called = {false};
            Invoker inv = () -> {
                called[0] = true;
                Transformers.invokeExact(original, frame);
            };
            if (errors[0] >= MAX_HOOK_ERRORS) {
                inv.call();
                return;
            }
            try {
                around.run(frame.accessor(), inv);
            } catch (Throwable t) {
                if (called[0]) throw t;
                errors[0] = fail(m, errors[0], t);
                inv.call();
            }
        }, EntryPointType.DIRECT);
        Log.d(TAG, "hooked " + m);
    }

    /** One stack trace on the first error, then terse lines, then silence for this hook. */
    private static int fail(Method m, int n, Throwable t) {
        if (n == 0) {
            Log.e(TAG, "hook " + m + " threw", t);
        } else {
            Log.e(TAG, "hook " + m + " threw again (" + (n + 1) + "/" + MAX_HOOK_ERRORS + "): " + t);
        }
        int next = n + 1;
        if (next == MAX_HOOK_ERRORS) Log.w(TAG, "muting hook " + m + " after " + MAX_HOOK_ERRORS + " errors");
        return next;
    }

    /** The only method named {@code name} in {@code c} or its superclasses (nearest wins). */
    static Method method(Class<?> c, String name) {
        return method(c, name, -1);
    }

    /** As {@link #method(Class, String)}, restricted to {@code argc} parameters (-1 = any). */
    static Method method(Class<?> c, String name, int argc) {
        Members ms = members(c);
        Map<Integer, Object> byArgc = ms.methods.get(name);
        if (byArgc == null) {
            Map<Integer, Object> fresh = new ConcurrentHashMap<>();
            Map<Integer, Object> prev = ms.methods.putIfAbsent(name, fresh);
            byArgc = prev != null ? prev : fresh;
        }
        // Small ints are cached Integers, so this boxing does not allocate either.
        Object v = byArgc.get(argc);
        if (v == null) {
            Method found = findMethod(c, name, argc);
            if (found != null) found.setAccessible(true);
            v = found == null ? MISSING : found;
            byArgc.put(argc, v);
        }
        if (v == MISSING) throw new IllegalStateException("no method " + name + " in " + c.getName());
        return (Method) v;
    }

    private static Method findMethod(Class<?> c, String name, int argc) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            Method found = null;
            for (Method m : Reflection.getHiddenMethods(k)) {
                if (!m.getName().equals(name) || (argc >= 0 && m.getParameterCount() != argc)) continue;
                if (found != null) throw new IllegalStateException("ambiguous " + k.getName() + "." + name);
                found = m;
            }
            if (found != null) return found;
        }
        return null;
    }

    static Field field(Class<?> c, String name) {
        Members ms = members(c);
        Object v = ms.fields.get(name);
        if (v == null) {
            Field found = findField(c, name);
            if (found != null) found.setAccessible(true);
            v = found == null ? MISSING : found;
            ms.fields.put(name, v);
        }
        if (v == MISSING) throw new IllegalStateException("no field " + name + " in " + c.getName());
        return (Field) v;
    }

    private static Field findField(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Field f : Reflection.getHiddenFields(k)) {
                if (f.getName().equals(name)) return f;
            }
        }
        return null;
    }

    private static Members members(Class<?> c) {
        Members ms = MEMBERS.get(c);
        if (ms != null) return ms;
        return MEMBERS.computeIfAbsent(c, k -> new Members());
    }

    @SuppressWarnings("unchecked")
    static <T> T get(Object o, String name) throws IllegalAccessException {
        return (T) field(o.getClass(), name).get(o);
    }

    @SuppressWarnings("unchecked")
    static <T> T call(Object o, String name, Object... args) throws ReflectiveOperationException {
        return (T) method(o.getClass(), name).invoke(o, args);
    }

    @SuppressWarnings("unchecked")
    static <T> T callStatic(Class<?> c, String name, Object... args) throws ReflectiveOperationException {
        return (T) method(c, name).invoke(null, args);
    }

    private H() {
    }
}
