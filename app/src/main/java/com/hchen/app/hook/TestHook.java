/*
 * This file is part of HookTool.
 *
 * HookTool is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 2.1 of the License, or
 * (at your option) any later version.
 *
 * HookTool is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with HookTool. If not, see <https://www.gnu.org/licenses/lgpl-2.1>.
 *
 * Copyright (C) 2024–2026 HChenX
 */
package com.hchen.app.hook;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.hooktool.AbsModule;
import com.hchen.hooktool.hook.AbsHook;

import java.util.HashMap;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Hook 功能的示例模块实现。
 * <p>
 * 继承自 {@link AbsModule}，是 HookTool 框架中具体 Hook 逻辑的承载单元。
 * <p>
 * 开发者应覆写 {@link #onPackageReady(XposedModuleInterface.PackageReadyParam)} 方法以注册 Hook 逻辑。
 *
 * @see AbsModule
 */
public class TestHook extends AbsModule {
    private Context context;

    @Override
    protected void onPackageReady(@NonNull XposedModuleInterface.PackageReadyParam param) {
        registerHooks();
    }

    @Override
    protected void onHotReloaded(@NonNull XposedModuleInterface.HotReloadedParam param) {
        Object savedState = param.getSavedInstanceState();
        if (savedState instanceof Map) {
            Object savedContext = ((Map<?, ?>) savedState).get("CONTEXT");
            if (savedContext instanceof Context) {
                context = (Context) savedContext;
            }
        }
        // 热更新后在本类（新代码）实例中重新注册 Hook；
        // 旧的 Hook 句柄已由框架解除，此处注册的是全新 AbsHook 实例，不会重复执行。
        registerHooks();
    }

    @SuppressLint("XposedNewApi")
    private void registerHooks() {
        hookMethod("com.hchen.test.Test",
            "test",
            String.class,
            new AbsHook() {
                private Context context;

                @Override
                public void before() {
                    super.before();
                }

                @Override
                public Object proceed(@NonNull XposedInterface.Chain chain) throws Throwable {
                    return super.proceed(chain);
                }

                @Override
                public void after() {
                    super.after();
                    Object thisObject = getThisObject();
                    if (thisObject != null) {
                        setField(thisObject, "test", true);
                    }
                }

                @Override
                public boolean onThrow(@NonNull AbsHook.StageEnum stage, @NonNull Throwable e) {
                    return super.onThrow(stage, e);
                }

                @Override
                public void onHotReloading(@Nullable Bundle extras, @NonNull Map<String, Object> state) {
                    state.put("CONTEXT_INNER", context);
                }

                @Override
                public void onHotReloaded(@Nullable Object thisObject, @NonNull Map<String, Object> inState) {
                    super.onHotReloaded(thisObject, inState);
                    Object savedContext = inState.get("CONTEXT_INNER");
                    if (savedContext instanceof Context) {
                        context = (Context) savedContext;
                    }
                    if (thisObject != null) {
                        setField(thisObject, "test", true);
                    }
                }
            }
        );
    }

    /**
     * 模块级热重载准备回调。
     * <p>
     * 在此保存需要在热重载后恢复的模块级状态数据。
     * 返回的 {@link Map} 会被 {@link com.hchen.hooktool.hook.HookRegistry#reloading(Bundle)}
     * 合并到全局快照中。
     *
     * @param extras 热重载附加数据，可能为 {@code null}
     * @return 模块级状态键值对
     */
    @Override
    protected Map<String, Object> onHotReloading(@Nullable Bundle extras) {
        Map<String, Object> map = new HashMap<>();
        map.put("CONTEXT", context);
        return map;
    }
}
