/*
 * This file is part of HookTool.

 * HookTool is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.

 * Copyright (C) 2023-2024 HookTool Contributions
 */
package com.hchen.hooktool.utils;

import com.hchen.hooktool.HCHook;
import com.hchen.hooktool.HCInit;
import com.hchen.hooktool.ToolRestrict;
import com.hchen.hooktool.tool.ActionTool;
import com.hchen.hooktool.tool.ChainTool;
import com.hchen.hooktool.tool.CoreTool;
import com.hchen.hooktool.tool.PrefsTool;

import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 这是本工具的读写数据类，请不要继承或重写。
 * <p>
 * This is the read and write data class of this tool, please do not extends or override.
 */
public class ToolData {
    // HCInit
    public static String mInitTag = "[Unknown]";
    public static int mInitLogLevel = HCInit.LOG_I;
    public static String spareTag = "Unknown";
    public static String modulePackageName = null;
    public static boolean autoObserveCall = false;
    public static boolean autoReload = true;
    public static IXposedHookZygoteInit.StartupParam startupParam = null;
    // HCHook
    public static XC_LoadPackage.LoadPackageParam lpparam = null;
    public static ClassLoader classLoader = null;
    public String mThisTag = null;
    public HCHook hcHook = null;
    public ActionTool actionTool = null;
    public CoreTool coreTool = null;
    public ChainTool chainTool = null;
    public PrefsTool prefsTool = null;
    public ConvertHelper convertHelper = null;

    private ToolData() {
    }

    public ToolData(ToolRestrict helper) {
    }

    public ClassLoader getClassLoader() {
        return classLoader;
    }

    public XC_LoadPackage.LoadPackageParam getLpparam() {
        return lpparam;
    }

    public HCHook getHCHook() {
        HCHook hcHook = this.hcHook;
        if (hcHook == null)
            throw new RuntimeException(mInitTag + "[" + getTag() + "][E]: HCHook is null!!");
        return hcHook;
    }

    public ActionTool getActionTool() {
        ActionTool actionTool = this.actionTool;
        if (actionTool == null)
            throw new RuntimeException(mInitTag + "[" + getTag() + "][E]: ActionTool is null!!");
        return actionTool;
    }

    public CoreTool getCoreTool() {
        CoreTool coreTool = this.coreTool;
        if (coreTool == null)
            throw new RuntimeException(mInitTag + "[" + getTag() + "][E]: CoreTool is null!!");
        return coreTool;
    }

    public ChainTool getChainTool() {
        ChainTool chain = this.chainTool;
        if (chain == null)
            throw new RuntimeException(mInitTag + "[" + getTag() + "][E]: CreateChain is null!!");
        return chain;
    }

    public PrefsTool getPrefsTool() {
        PrefsTool prefs = this.prefsTool;
        if (prefs == null)
            throw new RuntimeException(mInitTag + "[" + getTag() + "][E]: PrefsTool is null!!");
        return prefs;
    }

    public ConvertHelper getConvertHelper() {
        ConvertHelper convertHelper = this.convertHelper;
        if (convertHelper == null)
            throw new RuntimeException(mInitTag + "[" + getTag() + "][E]: ConvertHelper is null!!");
        return convertHelper;
    }

    public String getTag() {
        if (mThisTag != null) return mThisTag;
        return spareTag;
    }
}