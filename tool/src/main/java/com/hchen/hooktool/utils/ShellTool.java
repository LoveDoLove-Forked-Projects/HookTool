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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.hooktool.callback.ICommandListener;
import com.hchen.hooktool.callback.IExecListener;
import com.hchen.hooktool.data.ShellResult;
import com.hchen.hooktool.exception.UnexpectedException;
import com.hchen.hooktool.log.AndroidLog;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shell 命令执行工具类。
 * <p>
 * 提供同步（{@link #exec()}）和异步（{@link #async()}）两种 Shell 命令执行能力，
 * 支持 Root 和普通两种模式（默认分别使用 {@code su} / {@code sh}，可通过
 * {@link #setShellCommands} 自定义）。内部维护持久化的 Shell 进程流，
 * 通过自增命令 ID 与结束标记关联每条命令的输出，实现多命令执行时结果的正确分离。
 * <p>
 * 命令以 {@link CompletableFuture}{@code <ShellResult>} 作为统一执行句柄：同步与异步
 * 走同一条提交路径，仅调用方是否阻塞等待不同。每条命令独立持有结果，互不覆盖；
 * 命令必然收敛（配对完成 / 流结束兜底 / 命令超时兜底），不会永久阻塞。
 * <p>
 * 使用示例：
 * <pre>{@code
 *         ShellTool shellTool = ShellTool.obtain(true);
 *         ShellResult shellResult = shellTool.cmd("ls").exec();
 *         if (shellResult != null) {
 *             boolean result = shellResult.isSuccess();
 *         }
 *         shellTool.cmd("""
 *             if [[ 1 == 1 ]]; then
 *                 echo hello;
 *             elif [[ 1 == 2 ]]; then
 *                 echo world;
 *             fi
 *             """).exec();
 *         shellTool.enableSplicingMode()
 *             .cmd("if [[ true == true ]]; then")
 *             .cmd("  echo hello               ")
 *             .cmd("fi                         ")
 *             .exec();
 *         Future<ShellResult> future = shellTool.cmd("echo hello").async();
 *         shellTool.setExecListener(new IExecListener() {
 *             @Override
 *             public void output(@NonNull String command, @NonNull String exitCode, @NonNull String[] outputs) {
 *                 IExecListener.super.output(command, exitCode, outputs);
 *             }
 *         });
 *         ShellTool.close();
 * }
 * @author 焕晨HChen
 */
public final class ShellTool {
    private static final String TAG = "ShellTool";
    private static final byte[] LINE_BREAK = "\n".getBytes(StandardCharsets.UTF_8);
    /**
     * 同步 {@link #exec()} 阻塞等待结果的最大时长（毫秒）。
     */
    private static final long EXEC_TIMEOUT_MS = 10_000;
    /**
     * 命令级兜底超时（毫秒）：超过该时长仍未收敛的命令将以异常完成，防止命令句柄泄漏。
     */
    private static final long COMMAND_TIMEOUT_MS = 15_000;

    private static final ShellTool shellTool = new ShellTool();
    private static volatile boolean isRoot = false;
    private static volatile String[] shellCommands = new String[]{"su", "sh"};
    private static volatile IExecListener globalExecListeners;
    private static volatile ICommandListener globalCommandListener;
    private static volatile ShellImpl shellImpl;
    /**
     * 全局自增命令 ID，跨进程重建持续递增，永不复用。
     */
    private static final AtomicLong nextCommandId = new AtomicLong();

    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "HookTool-Shell-Scheduler");
        thread.setDaemon(true);
        return thread;
    });
    private static final ExecutorService ROOT_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "HookTool-RootCheck");
        thread.setDaemon(true);
        return thread;
    });

    private ShellTool() {
        shellImpl = new ShellImpl();
    }

    /**
     * 获取 {@link ShellTool} 单例并初始化 Shell 进程流。
     * <p>
     * 使用当前通过 {@link #setRoot(boolean)} 设定的模式启动 Shell 进程。
     *
     * @return {@link ShellTool} 单例实例
     */
    @NonNull
    public static ShellTool obtain() {
        shellImpl.init();
        return shellTool;
    }

    /**
     * 获取 {@link ShellTool} 单例，同时指定 Root 模式并初始化 Shell 进程流。
     *
     * @param isRoot {@code true} 使用 {@code su}（Root 模式），{@code false} 使用 {@code sh}（普通模式）
     * @return {@link ShellTool} 单例实例
     */
    @NonNull
    public static ShellTool obtain(boolean isRoot) {
        setRoot(isRoot);
        return obtain();
    }

    /**
     * 设置 Shell 的 Root 模式。
     *
     * @param isRoot {@code true} 启用 Root 模式，{@code false} 使用普通模式
     * @return {@link ShellTool} 单例实例，支持链式调用
     */
    @NonNull
    public static ShellTool setRoot(boolean isRoot) {
        ShellTool.isRoot = isRoot;
        return shellTool;
    }

    /**
     * 自定义 Shell 启动命令。
     * <p>
     * 数组长度必须为 2：第一个元素为 Root 模式命令，第二个为普通模式命令。
     * 数组的合法性在下次 {@link #obtain()} 初始化进程时校验，长度不足 2 或含空元素将抛出
     * {@link UnexpectedException}。
     *
     * @param commands Shell 命令数组，长度必须为 2
     * @return {@link ShellTool} 单例实例，支持链式调用
     */
    @NonNull
    public static ShellTool setShellCommands(@NonNull String[] commands) {
        shellCommands = commands.clone();
        return shellTool;
    }

    /**
     * 设置全局执行监听器，用于接收所有命令（同步与异步）的输出和错误回调。
     *
     * @param iExecListener 执行监听器实例；传 {@code null} 可取消监听
     * @return {@link ShellTool} 单例实例，支持链式调用
     */
    @NonNull
    public static ShellTool setExecListener(@Nullable IExecListener iExecListener) {
        globalExecListeners = iExecListener;
        return shellTool;
    }

    /**
     * 设置全局命令监听器，在命令执行前进行拦截。
     * <p>
     * 监听器的 {@code onCommand} 方法返回 {@code false} 时将阻止该命令的执行。
     *
     * @param listener 命令监听器实例；传 {@code null} 可取消监听
     * @return {@link ShellTool} 单例实例，支持链式调用
     */
    @NonNull
    public static ShellTool setCommandListener(@Nullable ICommandListener listener) {
        globalCommandListener = listener;
        return shellTool;
    }

    /**
     * 判断当前 Shell 进程流是否处于活跃状态。
     *
     * @return Shell 进程存活且读取线程正常运行时返回 {@code true}
     */
    public static boolean isActive() {
        return shellImpl.isActive();
    }

    /**
     * 关闭当前 Shell 进程流并释放所有相关资源。
     * <p>
     * 关闭流程包括：发送 {@code exit} 命令、等待进程退出、关闭输出流、终止读取线程。
     * 关闭后<strong>不会自动重建</strong>，需要重建请重新调用 {@link #obtain()}。
     */
    public static void close() {
        shellImpl.close();
    }

    /**
     * 启用命令拼接模式。
     * <p>
     * 启用后，通过 {@link #cmd(String)} 添加的多条命令将以换行符连接后拼接为一条命令一次性执行。
     * 拼接模式在执行一次后自动关闭。
     *
     * @return {@link ShellTool} 单例实例，支持链式调用
     */
    @NonNull
    public ShellTool enableSplicingMode() {
        shellImpl.enableSplicingMode();
        return shellTool;
    }

    /**
     * 添加一条待执行的 Shell 命令。
     * <p>
     * 若处于拼接模式，命令将被暂存到拼接列表中；否则直接覆盖当前待执行命令。
     *
     * @param cmd 命令字符串，不可为 {@code null}
     * @return {@link ShellTool} 单例实例，支持链式调用
     */
    @NonNull
    public ShellTool cmd(@NonNull String cmd) {
        shellImpl.cmd(cmd);
        return shellTool;
    }

    /**
     * 同步执行已添加的命令，阻塞当前线程直到命令执行完毕并返回结果。
     * <p>
     * 阻塞等待存在超时（默认 10 秒），超时、被拦截或未添加命令时返回 {@code null}，
     * 不会永久阻塞。若需长时间运行，请改用 {@link #async()}。
     *
     * @return 命令执行结果；未添加命令、被拦截、超时或异常时返回 {@code null}
     */
    @Nullable
    public ShellResult exec() {
        return shellImpl.exec();
    }

    /**
     * 异步执行已添加的命令，立即返回结果句柄，不阻塞当前线程。
     * <p>
     * 返回的 {@link Future} 在命令完成时携带有结果；可通过 {@code get()} 阻塞等待
     * 或 {@code whenComplete} 异步监听。同时命令完成会触发全局执行监听器
     * （{@link #setExecListener}）的对应回调。
     *
     * @return 命令结果的 {@link Future} 句柄；未添加命令或被拦截时返回 {@code null}
     */
    @Nullable
    public Future<ShellResult> async() {
        return shellImpl.submitCommand(null);
    }

    /**
     * 异步执行已添加的命令，并通过指定的监听器接收结果。
     *
     * @param iExecListener 用于接收本次命令执行结果的监听器，不为 {@code null}
     * @return 命令结果的 {@link Future} 句柄；未添加命令或被拦截时返回 {@code null}
     */
    @Nullable
    public Future<ShellResult> async(@NonNull IExecListener iExecListener) {
        return shellImpl.submitCommand(iExecListener);
    }

    // --------------------------------------- Root Check -------------------------------------------

    /**
     * 同步检查当前设备是否具备 Root 权限。
     *
     * @return 具备 Root 权限返回 {@code true}
     */
    public static boolean isRootAvailable() {
        return checkRootSync(null);
    }

    /**
     * 同步检查当前设备是否具备 Root 权限，并通过监听器返回检测结果。
     *
     * @param iExecListener 接收 Root 检测结果的监听器
     * @return 具备 Root 权限返回 {@code true}
     */
    public static boolean isRootAvailable(@NonNull IExecListener iExecListener) {
        return checkRootSync(iExecListener);
    }

    /**
     * 检查当前设备是否具备 Root 权限。
     * <p>
     * 历史方法：{@code sync} 参数已无意义，本方法始终执行<strong>真实同步检测</strong>并返回真实结果。
     * 需要异步检测请使用 {@link #isRootAvailableAsync(IExecListener)}。
     *
     * @param sync          已忽略，统一为真实同步检测
     * @param iExecListener 接收 Root 检测结果的监听器，可为 {@code null}
     * @return 具备 Root 权限返回 {@code true}
     * @deprecated 请使用 {@link #isRootAvailable()} 或 {@link #isRootAvailableAsync(IExecListener)}
     */
    @Deprecated
    public static boolean isRootAvailable(boolean sync, @Nullable IExecListener iExecListener) {
        return checkRootSync(iExecListener);
    }

    /**
     * 异步检查当前设备是否具备 Root 权限。
     * <p>
     * 在独立后台线程中执行检测，结果通过返回的 {@link CompletableFuture} 获取，
     * 同时通过监听器的 {@code onRootResult} 回调返回。
     *
     * @param iExecListener 接收 Root 检测结果的监听器，可为 {@code null}
     * @return 携带检测结果的 {@link CompletableFuture}，不为 {@code null}
     */
    @NonNull
    public static CompletableFuture<Boolean> isRootAvailableAsync(@Nullable IExecListener iExecListener) {
        return CompletableFuture.supplyAsync(() -> checkRootSync(iExecListener), ROOT_EXECUTOR);
    }

    /**
     * 通过执行 {@code su -c true} 并检查退出码，同步判断 Root 权限是否可用。
     *
     * @param iExecListener 接收 Root 检测结果的监听器，可为 {@code null}
     * @return 具备 Root 权限返回 {@code true}
     */
    private static boolean checkRootSync(@Nullable IExecListener iExecListener) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec("su -c true");
            // 丢弃子进程的 stdout/stderr，防止输出填满管道缓冲导致 waitFor 永久阻塞，
            // 并避免每次调用泄漏两个输入流。
            try (InputStream ignored = process.getInputStream();
                 InputStream ignoredErr = process.getErrorStream()) {
                int exitCode = process.waitFor();
                if (iExecListener != null) {
                    iExecListener.onRootResult(exitCode == 0, String.valueOf(exitCode));
                }
                return exitCode == 0;
            }
        } catch (IOException e) {
            AndroidLog.logE(TAG, "Error executing 'su -c true' for root check.", e);
            if (iExecListener != null) {
                iExecListener.onRootResult(false, "-1");
            }
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            AndroidLog.logE(TAG, "Root check ('su -c true') interrupted.", e);
            if (iExecListener != null) {
                iExecListener.onRootResult(false, "-1");
            }
            return false;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    // ----------------------------------------------------------------------------------------------

    /**
     * Shell 流内部实现类。
     * <p>
     * 负责管理 Shell 进程的完整生命周期：进程启动、命令提交、写入、同步等待、关闭与异常处理。
     * 命令统一经 {@link #submitCommand} 提交为 {@link CompletableFuture}，同步/异步共用同一路径。
     */
    final class ShellImpl {
        private final Object writeLock = new Object();
        private final AtomicBoolean brokenReported = new AtomicBoolean();
        private final List<String> splicingCommands = new ArrayList<>();

        private volatile boolean isSplicingMode = false;
        private String command = null;
        private volatile Process process = null;
        private volatile DataOutputStream os = null;
        private volatile StreamThread streamThread = null;
        private volatile String token = "";
        private volatile boolean closing = false;
        private volatile int generation = 0;

        private ShellImpl() {
        }

        private synchronized void init() {
            if (isActive()) return;
            validateShellCommands();

            closing = false;
            brokenReported.set(false);
            command = null;
            isSplicingMode = false;
            splicingCommands.clear();

            Process newProcess = null;
            try {
                newProcess = Runtime.getRuntime().exec(isRoot ? shellCommands[0] : shellCommands[1]);
                DataOutputStream newOs = new DataOutputStream(newProcess.getOutputStream());
                token = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
                generation++;
                process = newProcess;
                os = newOs;
                streamThread = new StreamThread(this, newProcess.getInputStream(), newProcess.getErrorStream());
                streamThread.start();
            } catch (IOException e) {
                // 已创建的进程在此销毁，避免子进程与管道泄漏。
                if (newProcess != null) {
                    newProcess.destroy();
                }
                process = null;
                os = null;
                streamThread = null;
                throw new UnexpectedException("Error initializing shell stream.", e);
            }
        }

        private void validateShellCommands() {
            String[] commands = shellCommands;
            if (commands == null || commands.length < 2
                || commands[0] == null || commands[0].isEmpty()
                || commands[1] == null || commands[1].isEmpty()) {
                throw new UnexpectedException(
                    "setShellCommands must provide a non-empty command array of length 2.");
            }
        }

        private synchronized void enableSplicingMode() {
            this.isSplicingMode = true;
        }

        private synchronized void cmd(@NonNull String cmd) {
            if (!isActive()) {
                throw new UnexpectedException("Shell stream is dead.");
            }
            if (isSplicingMode) {
                splicingCommands.add(cmd);
            } else {
                command = cmd;
            }
        }

        /**
         * 统一提交命令入口：将当前待执行命令（或拼接结果）写入 Shell 流并返回结果句柄。
         *
         * @param perCmdListener 本次命令专属监听器，可为 {@code null}
         * @return 命令结果句柄；无待执行命令或被命令监听器拦截时返回 {@code null}
         * @throws UnexpectedException 当 Shell 流已失效时抛出
         */
        @Nullable
        private synchronized CompletableFuture<ShellResult> submitCommand(@Nullable IExecListener perCmdListener) {
            if (!isActive()) {
                throw new UnexpectedException("Shell stream is dead.");
            }

            splicingCommandIfNeed();
            if (command == null) {
                return null;
            }
            if (globalCommandListener != null && !globalCommandListener.onCommand(command)) {
                command = null;
                return null;
            }

            String cmd = command;
            command = null;
            long id = nextCommandId.incrementAndGet();
            StreamThread.PendingCommand pc = new StreamThread.PendingCommand(id, generation, cmd, perCmdListener);
            streamThread.pending.put(id, pc);
            String marker = String.format(Locale.ROOT,
                "__HT_RET=$?; echo HTM_%s_%d,$__HT_RET; echo HTM_%s_%d,$__HT_RET 1>&2; unset __HT_RET",
                token, id, token, id
            );

            synchronized (writeLock) {
                boolean ok = write("{");
                ok &= writeAll(cmd.split("\n"));
                ok &= write("}");
                ok &= write(marker);
                if (!ok) {
                    streamThread.pending.remove(id);
                    pc.future.completeExceptionally(
                        new IOException("Failed to write command to shell stream: " + cmd));
                    return null;
                }
            }

            scheduleTimeout(pc);
            return pc.future;
        }

        @Nullable
        private ShellResult exec() {
            String cmd = command;
            CompletableFuture<ShellResult> future = submitCommand(null);
            if (future == null) {
                return null;
            }
            try {
                return future.get(EXEC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                AndroidLog.logW(TAG, "Shell exec timed out for command: " + cmd, e);
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                AndroidLog.logW(TAG, "Shell exec interrupted while waiting for result.", e);
                return null;
            } catch (ExecutionException e) {
                AndroidLog.logE(TAG, "Shell exec failed for command: " + cmd, e);
                return null;
            }
        }

        private void splicingCommandIfNeed() {
            if (!isSplicingMode) {
                return;
            }
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < splicingCommands.size(); i++) {
                if (i > 0) {
                    builder.append('\n');
                }
                builder.append(splicingCommands.get(i));
            }
            isSplicingMode = false;
            splicingCommands.clear();
            // 注意：builder.isEmpty() 依赖 CharSequence.isEmpty()（API 35），minSdk 30 下不可用，用 length() == 0
            command = builder.length() == 0 ? null : builder.toString();
        }

        /**
         * 为命令调度兜底超时任务：超时仍未收敛时以异常完成，防止命令句柄泄漏。
         *
         * @param pc 待调度的命令数据
         */
        private void scheduleTimeout(@NonNull StreamThread.PendingCommand pc) {
            pc.timeoutTask = SCHEDULER.schedule(() -> {
                if (pc.future.isDone()) {
                    return;
                }
                pc.future.completeExceptionally(new TimeoutException(
                    "Shell command timed out after " + COMMAND_TIMEOUT_MS + "ms: " + pc.command));
                StreamThread st = streamThread;
                if (st != null) {
                    st.pending.remove(pc.id);
                }
            }, COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }

        private boolean write(@NonNull String command) {
            return write(command.getBytes(StandardCharsets.UTF_8));
        }

        private boolean write(@NonNull byte[] bytes) {
            try {
                DataOutputStream output = os;
                if (output == null) {
                    return false;
                }
                output.write(bytes);
                output.write(LINE_BREAK);
                output.flush();
                return true;
            } catch (IOException e) {
                AndroidLog.logE(TAG, "Error writing bytes to shell stream.", e);
                return false;
            }
        }

        private boolean writeAll(@NonNull String[] commands) {
            try {
                DataOutputStream output = os;
                if (output == null) {
                    return false;
                }
                for (String cmd : commands) {
                    output.write(cmd.getBytes(StandardCharsets.UTF_8));
                    output.write(LINE_BREAK);
                }
                output.flush();
                return true;
            } catch (IOException e) {
                AndroidLog.logE(TAG, "Error writing commands to shell stream: " + java.util.Arrays.toString(commands), e);
                return false;
            }
        }

        private synchronized void close() {
            if (process == null && streamThread == null) {
                return;
            }
            closing = true;
            try {
                if (isActive()) {
                    synchronized (writeLock) {
                        write("exit");
                    }
                }

                if (process != null) {
                    try {
                        process.waitFor(3, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    process.destroy();
                    try {
                        process.waitFor(1, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }

                if (os != null) {
                    try {
                        os.close();
                    } catch (IOException e) {
                        AndroidLog.logE(TAG, "Error closing shell output stream.", e);
                    }
                }

                if (streamThread != null) {
                    for (StreamThread.PendingCommand pc : streamThread.pending.values()) {
                        if (!pc.future.isDone()) {
                            pc.future.completeExceptionally(new IOException("Shell closed."));
                        }
                        if (pc.timeoutTask != null) {
                            pc.timeoutTask.cancel(false);
                        }
                    }
                    streamThread.close();
                }
            } finally {
                process = null;
                os = null;
                streamThread = null;
                command = null;
                isSplicingMode = false;
                splicingCommands.clear();
            }
        }

        private synchronized boolean isActive() {
            if (streamThread == null || process == null || closing) {
                return false;
            }
            return process.isAlive() && streamThread.isActive();
        }

        /**
         * 处理 Shell 进程异常死亡：保证仅上报一次，完成在途命令并回调 {@code brokenPipe}，随后关闭进程流。
         * <strong>不会自动重建进程。</strong>
         *
         * @param leftoverErrors 进程死亡时残留的错误输出行
         */
        private void onBrokenPipe(@NonNull String[] leftoverErrors) {
            if (!brokenReported.compareAndSet(false, true)) {
                return;
            }
            StreamThread st = streamThread;
            if (st != null) {
                List<String> inFlight = new ArrayList<>();
                for (StreamThread.PendingCommand pc : st.pending.values()) {
                    inFlight.add(pc.command);
                }
                for (StreamThread.PendingCommand pc : st.pending.values()) {
                    if (!pc.future.isDone()) {
                        pc.future.completeExceptionally(
                            new IOException("Shell broken pipe: process exited unexpectedly."));
                    }
                    if (pc.timeoutTask != null) {
                        pc.timeoutTask.cancel(false);
                    }
                }
                if (globalExecListeners != null) {
                    try {
                        globalExecListeners.brokenPipe(
                            "Incorrect shell code causing pipeline rupture. In-flight commands: " + inFlight,
                            leftoverErrors
                        );
                    } catch (Throwable e) {
                        AndroidLog.logE(TAG, "Error during brokenPipe callback.", e);
                    }
                }
            }
            close();
        }
    }

    /**
     * Shell 标准输出和错误输出的读取线程管理类。
     * <p>
     * 通过两条独立的后台读取线程分别读取标准输出流与错误输出流，利用结束标记行关联命令。
     * 读取线程<strong>永不阻塞等待</strong>：普通行追加到当前流缓冲，标记行将缓冲移交对应命令；
     * 命令收敛由「双流配对 / 流结束兜底 / 命令超时兜底」三条路径保证。
     */
    final class StreamThread {
        private static final String[] EMPTY = new String[0];

        @NonNull
        private final ShellImpl shellImpl;
        @NonNull
        private final InputStream input;
        @NonNull
        private final InputStream error;
        private final Thread stdoutThread;
        private final Thread stderrThread;
        private final ConcurrentHashMap<Long, PendingCommand> pending = new ConcurrentHashMap<>();

        private StreamThread(@NonNull ShellImpl shellImpl, @NonNull InputStream inputStream,
                             @NonNull InputStream errorStream) {
            this.shellImpl = shellImpl;
            input = inputStream;
            error = errorStream;
            stdoutThread = new Thread(this::readStdout, "HookTool-Shell-Stdout");
            stderrThread = new Thread(this::readStderr, "HookTool-Shell-Stderr");
            stdoutThread.setDaemon(true);
            stderrThread.setDaemon(true);
        }

        private void start() {
            stdoutThread.start();
            stderrThread.start();
        }

        private boolean isActive() {
            return stdoutThread.isAlive() && stderrThread.isAlive();
        }

        private void readStdout() {
            List<String> buffer = new ArrayList<>();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(input))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (handleLine(line, false, buffer)) {
                        buffer = new ArrayList<>();
                    } else {
                        buffer.add(line);
                    }
                }
            } catch (IOException e) {
                if (!shellImpl.closing) {
                    AndroidLog.logE(TAG, "Error reading shell standard output stream.", e);
                }
            }
            onStreamEof(false, toArray(buffer));
        }

        private void readStderr() {
            List<String> buffer = new ArrayList<>();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(error))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (handleLine(line, true, buffer)) {
                        buffer = new ArrayList<>();
                    } else {
                        buffer.add(line);
                    }
                }
            } catch (IOException e) {
                if (!shellImpl.closing) {
                    AndroidLog.logE(TAG, "Error reading shell standard error stream.", e);
                }
            }
            onStreamEof(true, toArray(buffer));
        }

        /**
         * 处理一行输出：解析结束标记行并移交缓冲，普通行返回 {@code false} 由调用方入缓冲。
         *
         * @param line    当前读取的行
         * @param isError 是否来自错误输出流
         * @param buffer  当前流缓冲
         * @return 若是已处理的标记行返回 {@code true}（不应再入缓冲）
         */
        private boolean handleLine(@NonNull String line, boolean isError, @NonNull List<String> buffer) {
            Marker marker = parseMarker(line);
            if (marker == null) {
                return false;
            }
            handleMarker(marker, isError, buffer);
            return true;
        }

        @Nullable
        private Marker parseMarker(@NonNull String line) {
            String prefix = "HTM_" + shellImpl.token + "_";
            if (!line.startsWith(prefix)) {
                return null;
            }
            String body = line.substring(prefix.length());
            int comma = body.indexOf(',');
            if (comma < 0) {
                return null;
            }
            try {
                long id = Long.parseLong(body.substring(0, comma));
                String exitCode = body.substring(comma + 1).trim();
                return new Marker(id, exitCode);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        private void handleMarker(@NonNull Marker marker, boolean isError, @NonNull List<String> buffer) {
            PendingCommand pc = pending.get(marker.id);
            if (pc == null) {
                return; // 陈旧或已完成的命令
            }
            if (pc.generation != shellImpl.generation) {
                pending.remove(marker.id);
                return;
            }
            if (isError) {
                pc.errLines = toArray(buffer);
                pc.errArrived = true;
            } else {
                pc.outLines = toArray(buffer);
                pc.outArrived = true;
            }
            pc.exitCode = marker.exitCode;
            maybeComplete(pc);
        }

        /**
         * 双流标记均到达时组装结果并完成命令（含回调分发）。
         *
         * @param pc 待完成的命令数据
         */
        private void maybeComplete(@NonNull PendingCommand pc) {
            if (pc.future.isDone()) {
                return;
            }
            if (!pc.outArrived || !pc.errArrived) {
                return;
            }
            completeCommand(pc, new ShellResult(
                pc.command,
                pc.exitCode,
                pc.outLines != null ? pc.outLines : EMPTY,
                pc.errLines != null ? pc.errLines : EMPTY
            ));
        }

        /**
         * 完成命令：先分发监听器回调，再完成 {@link CompletableFuture}，最后清理登记与超时任务。
         * <p>
         * stdout/stderr 两个读取线程可能并发到达同一命令，通过 {@code completed} 原子标志
         * 保证命令只被完整执行一次（回调与清理均只发生一次）。
         *
         * @param pc     命令数据
         * @param result 组装好的命令结果
         */
        private void completeCommand(@NonNull PendingCommand pc, @NonNull ShellResult result) {
            if (pc.completed.compareAndSet(false, true)) {
                dispatchCallbacks(pc, result);
                pc.future.complete(result);
                pending.remove(pc.id);
                if (pc.timeoutTask != null) {
                    pc.timeoutTask.cancel(false);
                }
            }
        }

        /**
         * 按退出码分发 output/error 回调：先全局监听器，后命令专属监听器。
         *
         * @param pc     命令数据
         * @param result 命令结果
         */
        private void dispatchCallbacks(@NonNull PendingCommand pc, @NonNull ShellResult result) {
            boolean success = "0".equals(result.exitCode());
            if (globalExecListeners != null) {
                try {
                    if (success) {
                        globalExecListeners.output(result.command(), result.exitCode(), result.outputs());
                    } else {
                        globalExecListeners.error(result.command(), result.exitCode(), result.errors());
                    }
                } catch (Throwable e) {
                    AndroidLog.logW(TAG, "Error during global exec listener callback for command: " + result.command(), e);
                }
            }
            if (pc.perCmdListener != null) {
                try {
                    if (success) {
                        pc.perCmdListener.output(result.command(), result.exitCode(), result.outputs());
                    } else {
                        pc.perCmdListener.error(result.command(), result.exitCode(), result.errors());
                    }
                } catch (Throwable e) {
                    AndroidLog.logW(TAG, "Error during per-command exec listener callback for command: " + result.command(), e);
                }
            }
        }

        /**
         * 某条流结束时触发：进程异常死亡则上报 brokenPipe 并关闭；否则将缺失该流标记的在途命令以空数据完成。
         *
         * @param isError  是否错误流结束
         * @param leftover 流结束时残留的未归属输出行
         */
        private void onStreamEof(boolean isError, @NonNull String[] leftover) {
            if (shellImpl.closing) {
                return;
            }
            Process process = shellImpl.process;
            if (process != null && !process.isAlive()) {
                shellImpl.onBrokenPipe(leftover);
                return;
            }
            for (PendingCommand pc : pending.values()) {
                if (pc.future.isDone()) {
                    continue;
                }
                if (isError && !pc.errArrived) {
                    pc.errLines = EMPTY;
                    pc.errArrived = true;
                    maybeComplete(pc);
                } else if (!isError && !pc.outArrived) {
                    pc.outLines = EMPTY;
                    pc.outArrived = true;
                    maybeComplete(pc);
                }
            }
        }

        private void close() {
            Thread current = Thread.currentThread();
            interruptAndJoin(stdoutThread, current);
            interruptAndJoin(stderrThread, current);
            pending.clear();
        }

        private void interruptAndJoin(@NonNull Thread thread, @NonNull Thread current) {
            if (thread == current) {
                return;
            }
            thread.interrupt();
            try {
                thread.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @NonNull
        private String[] toArray(@NonNull List<String> list) {
            return list.toArray(new String[0]);
        }

        /**
         * 命令结束标记的解析结果。
         */
        private static final class Marker {
            final long id;
            final String exitCode;

            Marker(long id, @NonNull String exitCode) {
                this.id = id;
                this.exitCode = exitCode;
            }
        }

        /**
         * 单条命令的待处理数据：持有独立的 {@link CompletableFuture} 与收集缓冲。
         */
        static final class PendingCommand {
            final long id;
            final int generation;
            @NonNull
            final String command;
            @NonNull
            final CompletableFuture<ShellResult> future;
            @Nullable
            final IExecListener perCmdListener;
            /**
             * 命令是否已被完整执行一次（回调 + 完成 + 清理），用于双读取线程并发防护。
             */
            final AtomicBoolean completed = new AtomicBoolean();
            volatile boolean outArrived;
            volatile boolean errArrived;
            volatile String[] outLines;
            volatile String[] errLines;
            volatile String exitCode = "-1";
            volatile ScheduledFuture<?> timeoutTask;

            PendingCommand(long id, int generation, @NonNull String command,
                           @Nullable IExecListener perCmdListener) {
                this.id = id;
                this.generation = generation;
                this.command = command;
                this.future = new CompletableFuture<>();
                this.perCmdListener = perCmdListener;
            }
        }
    }
}
