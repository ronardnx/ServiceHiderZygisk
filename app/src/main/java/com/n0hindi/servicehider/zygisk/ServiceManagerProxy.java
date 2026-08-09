package com.n0hindi.servicehider.zygisk;

import android.util.Log;

import com.v7878.zygisk.ZygoteLoader;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class ServiceManagerProxy {
    private static final String TAG = "ServiceHiderZygisk";
    private static final String[] HIDDEN = {
            "profile",
            "lineageglobalactions",
            "lineagehardware",
            "lineagehealth",
            "lineagelivedisplay",
            "lineagetrust",
            "vendor.lineage.health.IChargingControl/default",
            "vendor.lineage.health.IFastCharge/default",
            "vendor.lineage.livedisplay.IPictureAdjustment/default",
            "vendor.lineage.touch.IGloveMode/default",
            "vendor.lineage.touch.IHighTouchPollingRate/default",
            "vendor.lineage.touch.ITouchscreenGesture/default",
            "vendor.lineage.livedisplay.IDisplayModes/default"
    };

    private static boolean installed;

    static synchronized void install() throws Throwable {
        if (installed) return;

        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        exemptHiddenApi();

        Field managerField = findField(serviceManager, "sServiceManager");
        if (managerField != null) {
            managerField.setAccessible(true);
            Object delegate = managerField.get(null);
            if (delegate == null) {
                Method getter = serviceManager.getDeclaredMethod("getIServiceManager");
                getter.setAccessible(true);
                delegate = getter.invoke(null);
            }
            if (delegate != null && !Proxy.isProxyClass(delegate.getClass())) {
                Class<?> iface = Class.forName("android.os.IServiceManager");
                InvocationHandler handler = new Handler(delegate);
                Object proxy = Proxy.newProxyInstance(
                        iface.getClassLoader(), new Class<?>[]{iface}, handler);
                managerField.set(null, proxy);
            }
        }

        installCacheFilter(serviceManager);
        hideLineageAssetPath();
        Thread cacheMonitor = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                try {
                    Thread.sleep(100L);
                    installCacheFilter(serviceManager);
                } catch (Throwable ignored) {
                    return;
                }
            }
        }, "ServiceHiderCache");
        cacheMonitor.setDaemon(true);
        cacheMonitor.start();
        installed = true;
        Log.i(TAG, "ServiceManager proxy installed in " + ZygoteLoader.getPackageName());
    }

    private static final class Handler implements InvocationHandler {
        private final Object delegate;

        Handler(Object delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();

            if (args != null && args.length > 0 && args[0] instanceof String
                    && isHidden((String) args[0])) {
                if ("getService".equals(name) || "getService2".equals(name)
                        || "checkService".equals(name) || "tryGetService".equals(name)) {
                    if (isAppCaller()) {
                        Log.i(TAG, "blocked " + name + "(" + args[0] + ") for app caller");
                        return null;
                    }
                }
            }

            Object result;
            try {
                method.setAccessible(true);
                result = method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }

            if ("listServices".equals(name)) {
                Object filtered = filterList(result);
                Log.i(TAG, "filtered listServices");
                return filtered;
            }
            return result;
        }
    }

    private static final String[] SYSTEM_PREFIXES = {
            "android.", "com.android.", "java.", "javax.", "dalvik.",
            "sun.", "libcore.", "lineageos.", "org.lineageos.",
            "com.n0hindi.servicehider.", "$Proxy"
    };

    private static boolean isAppCaller() {
        for (StackTraceElement ste : Thread.currentThread().getStackTrace()) {
            String cls = ste.getClassName();
            if (cls == null) continue;
            boolean system = false;
            for (String prefix : SYSTEM_PREFIXES) {
                if (cls.startsWith(prefix)) {
                    system = true;
                    break;
                }
            }
            if (!system) return true;
        }
        return false;
    }

    private static Object filterList(Object value) {
        if (value instanceof String[]) {
            String[] source = (String[]) value;
            List<String> filtered = new ArrayList<>(source.length);
            for (String item : source) {
                if (item != null && !isHidden(item)) filtered.add(item);
            }
            return filtered.toArray(new String[0]);
        }
        if (value instanceof List<?>) {
            List<?> source = (List<?>) value;
            List<Object> filtered = new ArrayList<>(source.size());
            for (Object item : source) {
                if (!(item instanceof String) || !isHidden((String) item)) {
                    filtered.add(item);
                }
            }
            return filtered;
        }
        return value;
    }

    private static boolean isHidden(String value) {
        for (String hidden : HIDDEN) {
            if (hidden.equals(value)) return true;
        }
        return false;
    }

    private static Field findField(Class<?> type, String name) {
        try {
            return type.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static void installCacheFilter(Class<?> serviceManager) {
        Field cache = findField(serviceManager, "sCache");
        if (cache == null) {
            Log.w(TAG, "sCache field not found");
            return;
        }
        try {
            cache.setAccessible(true);
            Object value = cache.get(null);
            if (value != null && Proxy.isProxyClass(value.getClass())) return;
            if (value instanceof Map<?, ?>) {
                final Map<String, Object> delegate = (Map<String, Object>) value;

                // Evict any hidden services that may already be cached so they
                // do not appear in subsequent key-enumeration results.
                for (String hidden : HIDDEN) delegate.remove(hidden);

                // We do NOT pre-populate null values here.  Doing so caused
                // ServiceManager.getService() to return null for services the
                // LineageOS framework needs during app-process initialisation
                // (e.g. "profile"), which led to a NullPointerException inside
                // ClientTransactionListenerController and killed the app on launch.
                // Direct getService() calls are therefore left untouched; only
                // bulk-enumeration paths (keySet / entrySet / values) are filtered
                // so that scanner tools cannot observe the hidden service names.

                Object filtered = Proxy.newProxyInstance(
                        Map.class.getClassLoader(),
                        new Class<?>[]{Map.class},
                        (proxy, method, args) -> {
                            final String mName = method.getName();

                            if (args != null && args.length > 0
                                    && args[0] instanceof String
                                    && isHidden((String) args[0])) {
                                if ("get".equals(mName) || "containsKey".equals(mName)) {
                                    if (isAppCaller()) {
                                        return "containsKey".equals(mName) ? false : null;
                                    }
                                }
                                if ("put".equals(mName) || "putIfAbsent".equals(mName)) {
                                    // Prevent hidden services from being re-cached
                                    return null;
                                }
                            }

                            Object result;
                            try {
                                method.setAccessible(true);
                                result = method.invoke(delegate, args);
                            } catch (InvocationTargetException e) {
                                throw e.getCause();
                            }

                            // Filter hidden keys out of bulk-iteration results so
                            // that serviceScan / similar tools cannot observe them
                            // through the cache map's keySet / entrySet / values.
                            if ("keySet".equals(mName) && result instanceof java.util.Set) {
                                java.util.Set<String> ks = new java.util.HashSet<>((java.util.Set<String>) result);
                                for (String hidden : HIDDEN) ks.remove(hidden);
                                return java.util.Collections.unmodifiableSet(ks);
                            }
                            if ("entrySet".equals(mName) && result instanceof java.util.Set) {
                                java.util.Set<Map.Entry<String, Object>> es =
                                        new java.util.HashSet<>((java.util.Set<Map.Entry<String, Object>>) result);
                                es.removeIf(e -> e != null && isHidden(e.getKey()));
                                return java.util.Collections.unmodifiableSet(es);
                            }
                            if ("values".equals(mName) && result instanceof java.util.Collection) {
                                java.util.List<Object> vs = new java.util.ArrayList<>();
                                for (Map.Entry<String, Object> e : delegate.entrySet()) {
                                    if (e != null && !isHidden(e.getKey())) vs.add(e.getValue());
                                }
                                return java.util.Collections.unmodifiableList(vs);
                            }
                            return result;
                        });
                cache.set(null, filtered);
                Log.i(TAG, "sCache filter installed");
            }
        } catch (Throwable t) {
            Log.w(TAG, "cache cleanup", t);
        }
    }

    private static void hideLineageAssetPath() {
        try {
            Class<?> assetManager = Class.forName("android.content.res.AssetManager");
            Field field = assetManager.getDeclaredField("LINEAGE_APK_PATH");
            field.setAccessible(true);
            try {
                field.set(null, "");
                return;
            } catch (Throwable ignored) {
                // Static-final fields require the Unsafe fallback below.
            }

            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            Object unsafe = unsafeField.get(null);
            Method base = unsafeClass.getMethod("staticFieldBase", Field.class);
            Method offset = unsafeClass.getMethod("staticFieldOffset", Field.class);
            Method putObject = unsafeClass.getMethod("putObject", Object.class, long.class, Object.class);
            putObject.invoke(unsafe, base.invoke(unsafe, field), offset.invoke(unsafe, field), "");
        } catch (Throwable t) {
            Log.w(TAG, "AssetManager signature", t);
        }
    }

    private static void exemptHiddenApi() {
        try {
            Class<?> vmRuntime = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = vmRuntime.getDeclaredMethod("getRuntime");
            Method exemptions = vmRuntime.getDeclaredMethod(
                    "setHiddenApiExemptions", String[].class);
            getRuntime.setAccessible(true);
            exemptions.setAccessible(true);
            exemptions.invoke(getRuntime.invoke(null), (Object) new String[]{"L"});
        } catch (Throwable ignored) {
        }
    }
}
