package com.hchen.hooktool.utils;

import com.hchen.hooktool.callback.IAction;
import com.hchen.hooktool.hc.HCHook;
import com.hchen.hooktool.log.XposedLog;
import com.hchen.hooktool.safe.Safe;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 这是字段的读写类，请不要继承重写。
 */
public class Utils extends XposedLog {
    protected Safe safe = null;
    protected HCHook hcHook = null;
    // public MethodTool methodTool = null;
    public static XC_LoadPackage.LoadPackageParam lpparam = null;
    public static ClassLoader classLoader = null;
    public static String TAG = null;
    public String mTAG = null;
    protected XC_MethodHook.MethodHookParam param = null;
    protected Object thisObject = null;
    protected Class<?> findClass = null;
    protected Field findField = null;
    // private Method method = null;
    protected IAction iAction = null;
    protected final ArrayList<Method> methods = new ArrayList<>();
    protected final ArrayList<Constructor<?>> constructors = new ArrayList<>();

    public void setMyTAG(String tag) {
        mTAG = tag;
        safe.setMyTAG(tag);
    }

    public String useTAG() {
        if (mTAG != null) return mTAG;
        return TAG;
    }
}
