package io.github.people11.bgside;

import static io.github.people11.bgside.H.TAG;

import android.util.Log;

import com.v7878.r8.annotations.DoNotObfuscate;
import com.v7878.r8.annotations.DoNotObfuscateType;
import com.v7878.r8.annotations.DoNotShrink;
import com.v7878.r8.annotations.DoNotShrinkType;

@DoNotObfuscateType
@DoNotShrinkType
public final class Main {
    /** Injected into system_server only. */
    @SuppressWarnings({"unused", "ConfusingMainMethod"})
    @DoNotShrink
    @DoNotObfuscate
    public static void main() {
        try {
            // The system server class loader (services.jar, oplus-services.jar) is handed to findStaticMain.
            H.hook(Class.forName("com.android.internal.os.RuntimeInit"), "findStaticMain", a -> {
                if (!"com.android.server.SystemServer".equals(a.getReference(0))) return false;
                ClassLoader loader = a.getReference(2);
                // After all services exist, so oplus classes are not initialized early.
                H.hook(loader.loadClass("com.android.server.SystemServer"), "startOtherServices", null,
                        unused -> {
                            ServerHooks.install(loader);
                            CustomHooks.install(loader);
                        });
                return false;
            }, null);
        } catch (Throwable t) {
            Log.e(TAG, "init failed", t);
        }
    }

    private Main() {
    }
}
