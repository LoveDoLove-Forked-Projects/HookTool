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
package com.hchen.hooktool.utils;


import static com.hchen.hooktool.core.CoreTool.getParameterTypes;

import androidx.annotation.NonNull;

import com.hchen.hooktool.helper.CoreHelper;

/**
 * Java 反射调用工具类门面。
 * <p>
 * 本类为纯工具类，所有方法均为静态方法，不允许实例化。内部将全部反射操作委托给底层
 * {@link CoreHelper} 实现，对外保留原有公开 API，行为不变。
 * <p>
 * 委托后的行为演进（均为预期内的升级）：
 * <ul>
 *     <li>方法查找采用"最佳匹配"（best-match）算法，可匹配基本类型宽化 / 装箱 / 拆箱 / 继承链上的方法</li>
 *     <li>字段查找支持接口常量（interface constant）及继承链 / 接口树的递归查找</li>
 *     <li>类解析支持基本类型名（如 {@code "int"}）、数组类型（如 {@code "int[]"}）、内部类 {@code $} 自动替换</li>
 * </ul>
 * <p>
 * 反射失败时抛出与 {@link CoreHelper} 一致的 {@link Error} 类型异常。
 *
 * @author 焕晨HChen
 */
public final class InvokeTool {
    private InvokeTool() {
    }

    // ---------------------------- 调用方法 --------------------------------

    /**
     * 通过反射调用指定对象实例的方法。
     *
     * @param instance       目标对象实例，不得为 {@code null}
     * @param methodName     方法名称
     * @param parameterTypes 参数类型数组（元素可为 {@link Class} 或类名字符串），用于匹配目标方法签名
     * @param args           传入方法的实际参数值
     * @return 方法执行后的返回值，类型由目标方法的返回类型决定，需调用方自行确认并转换
     * @throws NoSuchMethodError  未找到兼容方法时抛出
     * @throws IllegalAccessError 方法无法访问时抛出
     * @throws Throwable          目标方法执行时抛出的原始异常（经 CoreHelper 透传）
     */
    public static Object callMethod(@NonNull Object instance, @NonNull String methodName, @NonNull Object[] parameterTypes, @NonNull Object... args) {
        Class<?>[] types = getParameterTypes(instance.getClass().getClassLoader(), parameterTypes);
        return CoreHelper.callMethod(instance, methodName, types, args);
    }

    /**
     * 通过反射调用指定类的静态方法。
     *
     * @param clazz          目标类，不得为 {@code null}
     * @param methodName     方法名称
     * @param parameterTypes 参数类型数组（元素可为 {@link Class} 或类名字符串），用于匹配目标方法签名
     * @param args           传入方法的实际参数值
     * @return 方法执行后的返回值，类型由目标方法的返回类型决定，需调用方自行确认并转换
     * @throws NoSuchMethodError  未找到兼容方法时抛出
     * @throws IllegalAccessError 方法无法访问时抛出
     * @throws Throwable          目标方法执行时抛出的原始异常（经 CoreHelper 透传）
     */
    public static Object callStaticMethod(@NonNull Class<?> clazz, @NonNull String methodName, @NonNull Object[] parameterTypes, @NonNull Object... args) {
        Class<?>[] types = getParameterTypes(clazz.getClassLoader(), parameterTypes);
        return CoreHelper.callStaticMethod(clazz, methodName, types, args);
    }

    /**
     * 通过类全限定名反射调用静态方法，使用系统类加载器加载目标类。
     *
     * @param classPath      目标类的全限定名
     * @param methodName     方法名称
     * @param parameterTypes 参数类型数组（元素可为 {@link Class} 或类名字符串）
     * @param args           传入方法的实际参数值
     * @return 方法执行后的返回值，类型由目标方法的返回类型决定，需调用方自行确认并转换
     * @throws NoClassDefFoundError 目标类或参数类型类无法加载时抛出
     * @throws NoSuchMethodError    未找到兼容方法时抛出
     * @throws IllegalAccessError   方法无法访问时抛出
     */
    public static Object callStaticMethod(@NonNull String classPath, @NonNull String methodName, @NonNull Object[] parameterTypes, @NonNull Object... args) {
        return callStaticMethod(findClass(classPath), methodName, parameterTypes, args);
    }

    /**
     * 通过类全限定名和自定义类加载器反射调用静态方法。
     *
     * @param classPath      目标类的全限定名
     * @param classLoader    用于加载目标类的类加载器，可为 {@code null}
     * @param methodName     方法名称
     * @param parameterTypes 参数类型数组（元素可为 {@link Class} 或类名字符串）
     * @param args           传入方法的实际参数值
     * @return 方法执行后的返回值，类型由目标方法的返回类型决定，需调用方自行确认并转换
     * @throws NoClassDefFoundError 目标类或参数类型类无法加载时抛出
     * @throws NoSuchMethodError    未找到兼容方法时抛出
     * @throws IllegalAccessError   方法无法访问时抛出
     */
    public static Object callStaticMethod(@NonNull String classPath, ClassLoader classLoader, @NonNull String methodName, @NonNull Object[] parameterTypes, @NonNull Object... args) {
        return callStaticMethod(findClass(classPath, classLoader), methodName, parameterTypes, args);
    }

    // ---------------------------- 设置字段 --------------------------------

    /**
     * 通过反射设置指定对象实例的字段值。
     *
     * @param instance  目标对象实例，不得为 {@code null}
     * @param fieldName 字段名称
     * @param value     要设置的字段值
     * @throws NoSuchFieldError   未找到该字段时抛出
     * @throws IllegalAccessError 字段无法访问时抛出
     */
    public static void setField(@NonNull Object instance, @NonNull String fieldName, Object value) {
        CoreHelper.setObjectField(instance, fieldName, value);
    }

    /**
     * 通过反射获取指定对象实例的字段值。
     *
     * @param instance  目标对象实例，不得为 {@code null}
     * @param fieldName 字段名称
     * @return 字段的当前值，类型由目标字段的类型决定，需调用方自行确认并转换
     * @throws NoSuchFieldError   未找到该字段时抛出
     * @throws IllegalAccessError 字段无法访问时抛出
     */
    public static Object getField(@NonNull Object instance, @NonNull String fieldName) {
        return CoreHelper.getObjectField(instance, fieldName);
    }

    /**
     * 通过反射设置指定类的静态字段值。
     *
     * @param clazz     目标类，不得为 {@code null}
     * @param fieldName 字段名称
     * @param value     要设置的字段值
     * @throws NoSuchFieldError         未找到该字段时抛出
     * @throws IllegalAccessError       字段无法访问时抛出
     * @throws IllegalArgumentException 字段存在但不是静态字段时抛出
     */
    public static void setStaticField(@NonNull Class<?> clazz, @NonNull String fieldName, Object value) {
        CoreHelper.setStaticObjectField(clazz, fieldName, value);
    }

    /**
     * 通过类全限定名反射设置静态字段值，使用系统类加载器加载目标类。
     *
     * @param classPath 目标类的全限定名
     * @param fieldName 字段名称
     * @param value     要设置的字段值
     * @throws NoClassDefFoundError     目标类无法加载时抛出
     * @throws NoSuchFieldError         未找到该字段时抛出
     * @throws IllegalAccessError       字段无法访问时抛出
     * @throws IllegalArgumentException 字段存在但不是静态字段时抛出
     */
    public static void setStaticField(@NonNull String classPath, @NonNull String fieldName, Object value) {
        setStaticField(findClass(classPath), fieldName, value);
    }

    /**
     * 通过类全限定名和自定义类加载器反射设置静态字段值。
     *
     * @param classPath   目标类的全限定名
     * @param classLoader 用于加载目标类的类加载器，可为 {@code null}
     * @param fieldName   字段名称
     * @param value       要设置的字段值
     * @throws NoClassDefFoundError     目标类无法加载时抛出
     * @throws NoSuchFieldError         未找到该字段时抛出
     * @throws IllegalAccessError       字段无法访问时抛出
     * @throws IllegalArgumentException 字段存在但不是静态字段时抛出
     */
    public static void setStaticField(@NonNull String classPath, ClassLoader classLoader, @NonNull String fieldName, Object value) {
        setStaticField(findClass(classPath, classLoader), fieldName, value);
    }

    /**
     * 通过反射获取指定类的静态字段值。
     *
     * @param clazz     目标类，不得为 {@code null}
     * @param fieldName 字段名称
     * @return 字段的当前值，类型由目标字段的类型决定，需调用方自行确认并转换
     * @throws NoSuchFieldError         未找到该字段时抛出
     * @throws IllegalAccessError       字段无法访问时抛出
     * @throws IllegalArgumentException 字段存在但不是静态字段时抛出
     */
    public static Object getStaticField(@NonNull Class<?> clazz, @NonNull String fieldName) {
        return CoreHelper.getStaticObjectField(clazz, fieldName);
    }

    /**
     * 通过类全限定名反射获取静态字段值，使用系统类加载器加载目标类。
     *
     * @param classPath 目标类的全限定名
     * @param fieldName 字段名称
     * @return 字段的当前值，类型由目标字段的类型决定，需调用方自行确认并转换
     * @throws NoClassDefFoundError     目标类无法加载时抛出
     * @throws NoSuchFieldError         未找到该字段时抛出
     * @throws IllegalAccessError       字段无法访问时抛出
     * @throws IllegalArgumentException 字段存在但不是静态字段时抛出
     */
    public static Object getStaticField(@NonNull String classPath, @NonNull String fieldName) {
        return getStaticField(findClass(classPath), fieldName);
    }

    /**
     * 通过类全限定名和自定义类加载器反射获取静态字段值。
     *
     * @param classPath   目标类的全限定名
     * @param classLoader 用于加载目标类的类加载器，可为 {@code null}
     * @param fieldName   字段名称
     * @return 字段的当前值，类型由目标字段的类型决定，需调用方自行确认并转换
     * @throws NoClassDefFoundError     目标类无法加载时抛出
     * @throws NoSuchFieldError         未找到该字段时抛出
     * @throws IllegalAccessError       字段无法访问时抛出
     * @throws IllegalArgumentException 字段存在但不是静态字段时抛出
     */
    public static Object getStaticField(@NonNull String classPath, ClassLoader classLoader, @NonNull String fieldName) {
        return getStaticField(findClass(classPath, classLoader), fieldName);
    }

    // ---------------------------- 查找类 --------------------------------

    /**
     * 通过类全限定名查找并加载类，使用系统类加载器。
     *
     * @param classPath 类的全限定名
     * @return 加载成功的 {@link Class} 对象
     * @throws NoClassDefFoundError 若指定类未找到
     */
    @NonNull
    public static Class<?> findClass(@NonNull String classPath) {
        return findClass(classPath, null);
    }

    /**
     * 通过类全限定名和指定的类加载器查找并加载类。
     * <p>
     * 若 {@code classLoader} 参数为 {@code null}，则回退使用系统类加载器。
     *
     * @param classPath   类的全限定名
     * @param classLoader 用于加载类的类加载器，为 {@code null} 时使用系统类加载器
     * @return 加载成功的 {@link Class} 对象
     * @throws NoClassDefFoundError 若指定类未找到
     */
    @NonNull
    public static Class<?> findClass(@NonNull String classPath, ClassLoader classLoader) {
        if (classLoader == null) {
            classLoader = ClassLoader.getSystemClassLoader();
        }
        return CoreHelper.findClass(classPath, classLoader);
    }
}
