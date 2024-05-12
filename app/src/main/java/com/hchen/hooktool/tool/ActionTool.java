package com.hchen.hooktool.tool;

import static com.hchen.hooktool.log.XposedLog.logE;
import static com.hchen.hooktool.log.XposedLog.logW;
import static de.robv.android.xposed.callbacks.XCallback.PRIORITY_HIGHEST;

import com.hchen.hooktool.action.Action;
import com.hchen.hooktool.callback.IAction;
import com.hchen.hooktool.callback.IAllAction;
import com.hchen.hooktool.data.MemberData;
import com.hchen.hooktool.data.StateEnum;
import com.hchen.hooktool.utils.DataUtils;

import java.lang.reflect.Member;
import java.util.ArrayList;

import de.robv.android.xposed.XposedBridge;

public class ActionTool {
    private final DataUtils utils;
    private int count = 0;
    private int lastIndex = -1;
    private int classIndex = -1;
    // protected ArrayList<Member> members = null;

    public ActionTool(DataUtils data) {
        this.utils = data;
    }

    /**
     * 不太建议修改，因为会持久化设置，除非手动设置 -1 使此设置失效。
     */
    public ActionTool setClassIndex(int index) {
        if (index < 0) {
            logE(utils.getTAG(), "class index cant < 0!");
            return utils.getActionTool();
        }
        classIndex = index;
        return utils.getActionTool();
    }

    /**
     * {@link ActionTool#allAction(int, IAllAction)}
     */
    public MethodTool allAction(IAllAction iAllAction) {
        return allAction(-1, iAllAction);
    }

    /**
     * 使用全部回调接口
     */
    public MethodTool allAction(int methodIndex, IAllAction iAllAction) {
        return allAction(utils.getCount(), methodIndex, iAllAction);
    }

    /**
     * {@link ActionTool#allAction(int, IAllAction)}
     */
    public MethodTool allAction(int classIndex, int methodIndex, IAllAction iAllAction) {
        if (actionSafe("iAllAction", iAllAction)) {
            hookTool("allAction", classIndex, methodIndex, new IActionTool() {
                @Override
                public Action action(Member member) {
                    return allActionTool(member, iAllAction);
                }
            });
        }
        return utils.getMethodTool();
    }

    /**
     * {@link ActionTool#after(int, IAction)}
     */
    public MethodTool after(IAction iAction) {
        return after(-1, iAction);
    }

    /**
     * 使用 after
     */
    public MethodTool after(int methodIndex, IAction iAction) {
        return after(utils.getCount(), methodIndex, iAction);
    }


    /**
     * {@link ActionTool#after(int, IAction)}
     */
    public MethodTool after(int classIndex, int methodIndex, IAction iAction) {
        if (actionSafe("iAction after", iAction)) {
            hookTool("after", classIndex, methodIndex, new IActionTool() {
                @Override
                public Action action(Member member) {
                    return afterTool(member, iAction);
                }
            });
        }
        return utils.getMethodTool();
    }

    /**
     * {@link ActionTool#before(int, IAction)}
     */
    public MethodTool before(IAction iAction) {
        return before(-1, iAction);
    }

    /**
     * 使用 before
     */
    public MethodTool before(int methodIndex, IAction iAction) {
        return before(utils.getCount(), methodIndex, iAction);
    }

    /**
     * {@link ActionTool#before(int, IAction)}
     */
    public MethodTool before(int classIndex, int methodIndex, IAction iAction) {
        if (actionSafe("iAction before", iAction)) {
            hookTool("before", classIndex, methodIndex, new IActionTool() {
                @Override
                public Action action(Member member) {
                    return beforeTool(member, iAction);
                }
            });
        }
        return utils.getMethodTool();
    }


    /**
     * {@link ActionTool#returnResult(int, Object)}
     */
    public void returnResult(final Object result) {
        returnResult(-1, result);
    }

    /**
     * 直接返回指定值
     */
    public void returnResult(int methodIndex, final Object result) {
        returnResult(utils.getCount(), methodIndex, result);
    }

    /**
     * {@link ActionTool#returnResult(int, Object)}
     */
    public void returnResult(int classIndex, int methodIndex, final Object result) {
        hookTool("returnResult", classIndex, methodIndex, new IActionTool() {
            @Override
            public Action action(Member member) {
                return new Action(utils.getTAG()) {
                    @Override
                    protected void before(MethodHookParam param) {
                        param.setResult(result);
                    }
                };
            }
        });
    }

    /**
     * {@link ActionTool#doNothing(int)}
     */
    public void doNothing() {
        doNothing(-1);
    }

    /**
     * 是 hook 方法失效
     */
    public void doNothing(int methodIndex) {
        doNothing(utils.getCount(), methodIndex);
    }

    /**
     * {@link ActionTool#doNothing(int)}
     */
    public void doNothing(int classIndex, int methodIndex) {
        hookTool("doNothing", classIndex, methodIndex, new IActionTool() {
            @Override
            public Action action(Member member) {
                return new Action(utils.getTAG(), PRIORITY_HIGHEST * 2) {
                    @Override
                    protected void before(MethodHookParam param) {
                        param.setResult(null);
                    }
                };
            }
        });
    }

    private void hookTool(String name, int classIndex, int methodIndex, IActionTool tool) {
        boolean useMethodIndex = methodIndex != -1;
        if (this.classIndex != -1) {
            classIndex = this.classIndex;
        }
        if (utils.members.size() > classIndex) {
            MemberData data = utils.members.get(classIndex);
            if (data != null) {
                if (data.mClass == null) {
                    logW(utils.getTAG(), "index: " + classIndex + " this data class is null!");
                }
                if (lastIndex == -1) lastIndex = classIndex;
                else if (lastIndex != classIndex) {
                    lastIndex = classIndex;
                    count = 0;
                }
                int size = data.memberMap.size();
                ArrayList<Member> members = null;
                if (size >= 2) {
                    if (count + 1 > size) {
                        logW(utils.getTAG(), name + " count > index, cant get memberMap! calss: " + data.mClass + "index: "
                                + classIndex + " size: " + size + " count: " + count);
                        return;
                    }
                    count = data.count;
                    // utils.setMethodCount(data.count);
                    members = data.memberMap.get(useMethodIndex ? methodIndex : count);
                    // count = count + 1;
                } else {
                    members = data.memberMap.get(0);
                    data.count = 0;
                    // count = 1;
                }
                if (members == null) {
                    logW(utils.getTAG(), name + " don't have anything can hook. class is: " + data.mClass);
                    return;
                }
                if (isHooked(name, data, members)) {
                    logW(utils.getTAG(), "this method or constructor is hooked [" + name + "]! members: " + members);
                    return;
                }
                for (Member member : members) {
                    try {
                        XposedBridge.hookMethod(member, tool.action(member));
                    } catch (Throwable e) {
                        logE(utils.getTAG(), name + " hook method: " + member + " e: " + e);
                    }
                }
                if (!useMethodIndex) data.count = count + 1;
                setState(name, data, members);
                utils.members.put(classIndex, data);
            } else {
                logW(utils.getTAG(), name + " member data is null!");
            }
        } else {
            logW(utils.getTAG(), name + " members size < index!");
        }
    }

    private void hookTool(String name, int methodIndex, IActionTool tool) {
        hookTool(name, utils.getCount(), methodIndex, tool);
    }

    private boolean isHooked(String name, MemberData data, ArrayList<Member> members) {
        if (data.stateMap.get(members) == StateEnum.ALL) return true;
        switch (name) {
            case "after" -> {
                return data.stateMap.get(members) == StateEnum.AFTER;
            }
            case "before", "returnResult", "doNothing" -> {
                return data.stateMap.get(members) == StateEnum.BEFORE;
            }
            case "allAction" -> {
                if (data.stateMap.get(members) == StateEnum.AFTER ||
                        data.stateMap.get(members) == StateEnum.BEFORE) {
                    return true;
                }
            }
            default -> {
                return false;
            }
        }
        return false;
    }

    private void setState(String name, MemberData data, ArrayList<Member> members) {
        switch (name) {
            case "after" -> {
                data.stateMap.put(members, StateEnum.AFTER);
            }
            case "before", "returnResult", "doNothing" ->
                    data.stateMap.put(members, StateEnum.BEFORE);
            case "allAction" -> data.stateMap.put(members, StateEnum.ALL);
        }
    }

    private Action allActionTool(Member member, IAllAction iAllAction) {
        ParamTool<Object> paramTool = new ParamTool<>(member, utils.getTAG());
        StaticTool<Object> staticTool = new StaticTool<>(utils.getClassLoader(), utils.getTAG());
        return new Action(utils.getTAG()) {
            @Override
            protected void before(MethodHookParam param) {
                paramTool.setParam(param);
                iAllAction.before(paramTool, staticTool);
            }

            @Override
            protected void after(MethodHookParam param) {
                paramTool.setParam(param);
                iAllAction.after(paramTool, staticTool);
            }
        };
    }

    private Action afterTool(Member member, IAction iAction) {
        ParamTool<Object> paramTool = new ParamTool<>(member, utils.getTAG());
        StaticTool<Object> staticTool = new StaticTool<>(utils.getClassLoader(), utils.getTAG());
        return new Action(utils.getTAG()) {
            @Override
            protected void after(MethodHookParam param) {
                paramTool.setParam(param);
                iAction.action(paramTool, staticTool);
            }
        };
    }

    private Action beforeTool(Member member, IAction iAction) {
        ParamTool<Object> paramTool = new ParamTool<>(member, utils.getTAG());
        StaticTool<Object> staticTool = new StaticTool<>(utils.getClassLoader(), utils.getTAG());
        return new Action(utils.getTAG()) {
            @Override
            protected void before(MethodHookParam param) {
                paramTool.setParam(param);
                iAction.action(paramTool, staticTool);
            }
        };
    }

    public boolean actionSafe(String name, Object iAction) {
        if (iAction == null) {
            logW(utils.getTAG(), name + " is null!");
            return false;
        }
        return true;
    }

    private interface IActionTool {
        Action action(Member member);
    }

    public MethodTool methodTool() {
        return utils.getMethodTool();
    }

    // 优化调用，只提供基本用法，详细用法请获取工具类对象
    public ActionTool getMethod(String name, Class<?>... clzzs) {
        return utils.getMethodTool().getMethod(name, clzzs);
    }

    public ActionTool getConstructor(Class<?>... obj) {
        return utils.getMethodTool().getConstructor(obj);
    }
}
