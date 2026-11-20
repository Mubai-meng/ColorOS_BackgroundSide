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

/**
 * Thin helpers over AndroidVMTools + hidden-API reflection. Arg index 0 is `this` for instance methods.
 */
final class H {
    static final String TAG = "BgSide";
    static final int RET = RETURN_VALUE_IDX;

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
        Hooks.hook(m, EntryPointType.CURRENT, (original, frame) -> {
            var a = frame.accessor();
            boolean skip = false;
            if (before != null) {
                try {
                    skip = before.run(a);
                } catch (Throwable t) {
                    Log.e(TAG, "before " + m, t);
                }
            }
            try {
                if (!skip) Transformers.invokeExact(original, frame);
            } finally {
                if (after != null) {
                    try {
                        after.run(a);
                    } catch (Throwable t) {
                        Log.e(TAG, "after " + m, t);
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
        Method m = method(c, name);
        Hooks.hook(m, EntryPointType.CURRENT, (original, frame) -> {
            boolean[] called = {false};
            Invoker inv = () -> {
                called[0] = true;
                Transformers.invokeExact(original, frame);
            };
            try {
                around.run(frame.accessor(), inv);
            } catch (Throwable t) {
                if (called[0]) throw t;
                Log.e(TAG, "around " + m, t);
                inv.call();
            }
        }, EntryPointType.DIRECT);
        Log.d(TAG, "hooked " + m);
    }

    /** The only method named {@code name} in {@code c} or its superclasses (nearest wins). */
    static Method method(Class<?> c, String name) {
        return method(c, name, -1);
    }

    /** As {@link #method(Class, String)}, restricted to {@code argc} parameters (-1 = any). */
    static Method method(Class<?> c, String name, int argc) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            Method found = null;
            for (Method m : Reflection.getHiddenMethods(k)) {
                if (!m.getName().equals(name) || (argc >= 0 && m.getParameterCount() != argc)) continue;
                if (found != null) throw new IllegalStateException("ambiguous " + k.getName() + "." + name);
                found = m;
            }
            if (found != null) {
                found.setAccessible(true);
                return found;
            }
        }
        throw new IllegalStateException("no method " + c.getName() + "." + name);
    }

    static Field field(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Field f : Reflection.getHiddenFields(k)) {
                if (f.getName().equals(name)) {
                    f.setAccessible(true);
                    return f;
                }
            }
        }
        throw new IllegalStateException("no field " + c.getName() + "." + name);
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
