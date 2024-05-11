package com.hchen.hooktool.tool;

import static com.hchen.hooktool.log.XposedLog.logE;
import static com.hchen.hooktool.log.XposedLog.logW;

import android.support.annotation.Nullable;

import com.hchen.hooktool.HCHook;
import com.hchen.hooktool.data.MemberData;
import com.hchen.hooktool.utils.DataUtils;

import java.lang.reflect.Field;

import de.robv.android.xposed.XposedHelpers;

public class FieldTool {
    private final DataUtils utils;
    private Class<?> toClass;

    public FieldTool(DataUtils utils) {
        this.utils = utils;
    }

    public FieldTool reset() {
        utils.reset();
        return utils.getFieldTool();
    }

    public FieldTool next() {
        utils.next();
        return utils.getFieldTool();
    }

    public FieldTool back() {
        utils.back();
        return utils.getFieldTool();
    }

    /**
     * 按顺序获取指定字段。
     */
    public FieldTool findField(String name) {
        return findIndexField(utils.next, name);
    }

    /**
     * 按索引获取指定字段。
     */
    public FieldTool findIndexField(int index, String name) {
        utils.findField = null;
        toClass = null;
        if (utils.classes.isEmpty()) {
            logW(utils.getTAG(), "The class list is empty!");
            return utils.getFieldTool();
        }
        if (utils.classes.size() < index) {
            logW(utils.getTAG(), "index > class size, can't find field!");
            return utils.getFieldTool();
        }
        MemberData data = utils.classes.get(index);
        if (data == null) {
            logW(utils.getTAG(), "data is null, cant find field: " + name + " index: " + index);
            return utils.getFieldTool();
        }
        Class<?> c = data.mClass;
        if (c == null) {
            logW(utils.getTAG(), "findField but class is null!");
            return utils.getFieldTool();
        }
        try {
            utils.findField = XposedHelpers.findField(c, name);
            data.mField = utils.findField;
            toClass = c;
        } catch (NoSuchFieldError e) {
            logE(utils.getTAG(), "Failed to get claim field: " + name + " class: " + utils.findClass + " e: " + e);
        }
        return utils.getFieldTool();
    }

    /**
     * 获取查找到的字段，需要在下次查找前调用，否则被覆盖。
     */
    @Nullable
    public Field getField() {
        return utils.findField;
    }

    /**
     * 设置指定字段。
     */
    public FieldTool set(Object value) {
        if (utils.findField != null) {
            utils.findField.setAccessible(true);
            try {
                utils.findField.set(null, value);
            } catch (IllegalAccessException e) {
                logE(utils.getTAG(), "set: " + utils.findField + " e: " + e);
            }
        } else logW(utils.getTAG(), "findField is null!");
        return utils.getFieldTool();
    }

    /**
     * 获取指定字段。
     */
    public <T> T get() {
        if (utils.findField != null) {
            utils.findField.setAccessible(true);
            try {
                return (T) utils.findField.get(null);
            } catch (IllegalAccessException e) {
                logE(utils.getTAG(), "get: " + utils.findField + " e: " + e);
            }
        } else logW(utils.getTAG(), "findField is null!");
        return null;
    }

    public HCHook hcHook() {
        return utils.getHCHook();
    }

    public ClassTool classTool() {
        return utils.getClassTool();
    }

    public MethodTool methodTool() {
        return utils.getMethodTool();
    }
}
