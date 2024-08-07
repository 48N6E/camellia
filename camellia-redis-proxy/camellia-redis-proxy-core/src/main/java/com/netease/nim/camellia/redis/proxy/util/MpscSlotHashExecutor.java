package com.netease.nim.camellia.redis.proxy.util;

import com.netease.nim.camellia.tools.executor.CamelliaExecutor;
import com.netease.nim.camellia.tools.executor.CamelliaExecutorMonitor;
import com.netease.nim.camellia.tools.utils.MathUtil;
import io.netty.util.concurrent.FastThreadLocalThread;
import org.jctools.queues.MpscBlockingConsumerArrayQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Created by caojiajun on 2024/6/6
 */
public class MpscSlotHashExecutor implements CamelliaExecutor {

    private static final Logger logger = LoggerFactory.getLogger(MpscSlotHashExecutor.class);
    private static final RejectedExecutionHandler defaultRejectedPolicy = new AbortPolicy();

    private final String name;
    private final int poolSize;
    private final boolean poolSizeIs2Power;
    private final AtomicLong workerIdGen = new AtomicLong(1);
    private final List<WorkThread> workThreads;
    private final RejectedExecutionHandler rejectedExecutionHandler;

    public MpscSlotHashExecutor(String name, int poolSize, int queueSize) {
        this(name, poolSize, queueSize, defaultRejectedPolicy, null);
    }

    public MpscSlotHashExecutor(String name, int poolSize, int queueSize, RejectedExecutionHandler rejectedExecutionHandler) {
        this(name, poolSize, queueSize, rejectedExecutionHandler, null);
    }

    public MpscSlotHashExecutor(String name, int poolSize, int queueSize, RejectedExecutionHandler rejectedExecutionHandler, Runnable workThreadInitCallback) {
        this.name = CamelliaExecutorMonitor.genExecutorName(name);
        this.workThreads = new ArrayList<>(poolSize);
        for (int i=0; i<poolSize; i++) {
            WorkThread workThread = new WorkThread(this, queueSize, workThreadInitCallback);
            workThread.start();
            this.workThreads.add(workThread);
        }
        this.poolSize = poolSize;
        this.poolSizeIs2Power = MathUtil.is2Power(poolSize);
        this.rejectedExecutionHandler = rejectedExecutionHandler;
        logger.info("MpscSlotHashExecutor start success, name = {}, poolSize = {}, queueSize = {}", name, poolSize, queueSize);
    }

    /**
     * 根据hashKey计算index
     * @param key key
     * @return index
     */
    public int hashIndex(String key) {
        return hashIndex(key.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 根据hashKey计算index
     * @param key key
     * @return index
     */
    public int hashIndex(byte[] key) {
        int slot = RedisClusterCRC16Utils.getSlot(key);
        return MathUtil.mod(poolSizeIs2Power, slot, poolSize);
    }

    /**
     * 根据hashKey选取一个固定的工作线程执行一个任务
     * @param key key
     * @param runnable 无返回结果的任务
     * @return 任务结果
     */
    public Future<Void> submit(byte[] key, Runnable runnable) {
        int index = hashIndex(key);
        FutureTask<Void> task = new FutureTask<>(runnable, null);
        boolean success = workThreads.get(index).submit(task);
        if (!success) {
            rejectedExecutionHandler.rejectedExecution(task, this);
        }
        return task;
    }

    /**
     * 根据hashKey选取一个固定的工作线程执行一个任务
     * @param key key
     * @param runnable 无返回结果的任务
     * @return 任务结果
     */
    public Future<Void> submit(String key, Runnable runnable) {
        return submit(key.getBytes(StandardCharsets.UTF_8), runnable);
    }

    /**
     * 根据hashKey选取一个固定的工作线程执行一个任务
     * @param key key
     * @param callable 有返回结果的任务
     * @return 任务结果
     * @param <T> 返回结果的类型
     */
    public <T> Future<T> submit(byte[] key, Callable<T> callable) {
        int index = hashIndex(key);
        FutureTask<T> task = new FutureTask<>(callable);
        boolean success = workThreads.get(index).submit(task);
        if (!success) {
            rejectedExecutionHandler.rejectedExecution(task, this);
        }
        return task;
    }

    /**
     * 根据hashKey选取一个固定的工作线程执行一个任务
     * @param key key
     * @param callable 有返回结果的任务
     * @return 任务结果
     * @param <T> 返回结果的类型
     */
    public <T> Future<T> submit(String key, Callable<T> callable) {
        return submit(key.getBytes(StandardCharsets.UTF_8), callable);
    }

    @Override
    public String getName() {
        return name;
    }

    public int getPoolSize() {
        return poolSize;
    }

    /**
     * 获取某个hashKey下的等待队列大小
     * @param hashKey hashKey
     * @return 大小
     */
    public int getQueueSize(String hashKey) {
        return getQueueSize(hashKey.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 获取某个hashKey下的等待队列大小
     * @param hashKey hashKey
     * @return 大小
     */
    public int getQueueSize(byte[] hashKey) {
        int index = Math.abs(Arrays.hashCode(hashKey)) % workThreads.size();
        WorkThread workThread = workThreads.get(index);
        return workThread.queueSize();
    }

    /**
     * 获取等待队列的大小
     * @return 大小
     */
    public int getQueueSize() {
        int queueSize = 0;
        for (WorkThread workThread : workThreads) {
            queueSize += workThread.queueSize();
        }
        return queueSize;
    }

    public static interface RejectedExecutionHandler {
        void rejectedExecution(Runnable runnable, MpscSlotHashExecutor executor);
    }

    public static class AbortPolicy implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable runnable, MpscSlotHashExecutor executor) {
            throw new RejectedExecutionException("Task " + runnable.toString() + " rejected from " + executor.getName());
        }
    }

    public static class DiscardPolicy implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable runnable, MpscSlotHashExecutor executor) {
            logger.warn("Task " + runnable.toString() + " is discard from " + executor.getName());
        }
    }

    public static class CallerRunsPolicy implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable runnable, MpscSlotHashExecutor executor) {
            runnable.run();
        }
    }

    private static class WorkThread extends FastThreadLocalThread {

        private final BlockingQueue<FutureTask<?>> queue;
        private final Runnable initCallback;
        private final MpscSlotHashExecutor executor;

        public WorkThread(MpscSlotHashExecutor executor, int queueSize, Runnable initCallback) {
            this.executor = executor;
            this.queue = new MpscBlockingConsumerArrayQueue<>(queueSize);
            setName("mpsc-slot-hash-executor-" + executor.getName() + "-" + executor.workerIdGen.getAndIncrement());
            this.initCallback = initCallback;
        }

        public boolean submit(FutureTask<?> task) {
            return queue.offer(task);
        }

        public int queueSize() {
            return queue.size();
        }

        @Override
        public void run() {
            if (initCallback != null) {
                initCallback.run();
            }
            while (true) {
                try {
                    FutureTask<?> task = queue.poll(1, TimeUnit.SECONDS);
                    if (task != null) {
                        task.run();
                    }
                } catch (Exception e) {
                    logger.error("MpscSlotHashExecutor execute task error, name = {}", executor.name, e);
                }
            }
        }
    }
}
