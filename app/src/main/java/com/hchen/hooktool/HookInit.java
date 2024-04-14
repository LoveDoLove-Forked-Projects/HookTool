package com.hchen.hooktool;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookInit {
    public static XC_LoadPackage.LoadPackageParam lpparam = null;
    public static ClassLoader classLoader = null;
    public static boolean canUseSystemClassLoader = false;
    public static String packageName;
    private static String thisTAG = "[HChen][HookInit]: ";
    public static String TAG = null;

    public static void setTAG(String tag) {
        thisTAG = "[HChen]" + "[" + tag + "]: ";
        TAG = tag;
    }

    public static void setCanUseSystemClassLoader(boolean use) {
        canUseSystemClassLoader = use;
    }

    /**
     * 请在初始化时调用。
     */
    public static void initLoadPackageParam(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        lpparam = loadPackageParam;
        classLoader = loadPackageParam.classLoader;
        packageName = lpparam.packageName;
    }

    public static XC_LoadPackage.LoadPackageParam getLoadPackageParam() throws Throwable {
        if (lpparam != null) return lpparam;
        throw new Throwable("Failed to obtain LoadPackageParam, it is null!");
    }

    public static ClassLoader getClassLoader() throws Throwable {
        if (classLoader != null) return classLoader;
        if (canUseSystemClassLoader) {
            return getSystemClassLoader();
        }
        throw new Throwable("Failed to obtain ClassLoader! It is null!");
    }

    public static ClassLoader getSystemClassLoader() {
        return ClassLoader.getSystemClassLoader();
    }

    public static boolean isInitDone() {
        // if (lpparam == null) return false;
        if (canUseSystemClassLoader) return true;
        return classLoader != null;
    }
}
