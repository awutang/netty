/*
 * Copyright 2013 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.util.concurrent;

import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@SuppressWarnings("ComparableImplementedButEqualsNotOverridden")
final class ScheduledFutureTask<V> extends PromiseTask<V> implements ScheduledFuture<V> {
    private static final AtomicLong nextTaskId = new AtomicLong();

    // static final成员变量，在类加载的准备阶段(只要用到了类就会触发类加载)就将赋值语句执行
    private static final long START_TIME = System.nanoTime();

    static long nanoTime() {
        return System.nanoTime() - START_TIME;
    }

    static long deadlineNanos(long delay) {
        return nanoTime() + delay;
    }

    private final long id = nextTaskId.getAndIncrement();
    private final Queue<ScheduledFutureTask<?>> delayedTaskQueue;
    private long deadlineNanos;
    /* 0 - no repeat, >0 - repeat at fixed rate, <0 - repeat with fixed delay */
    private final long periodNanos;

    ScheduledFutureTask(
            EventExecutor executor, Queue<ScheduledFutureTask<?>> delayedTaskQueue,
            Runnable runnable, V result, long nanoTime) {

        this(executor, delayedTaskQueue, toCallable(runnable, result), nanoTime);
    }

    ScheduledFutureTask(
            EventExecutor executor, Queue<ScheduledFutureTask<?>> delayedTaskQueue,
            Callable<V> callable, long nanoTime, long period) {

        super(executor, callable);
        if (period == 0) {
            throw new IllegalArgumentException("period: 0 (expected: != 0)");
        }
        this.delayedTaskQueue = delayedTaskQueue;
        deadlineNanos = nanoTime;
        periodNanos = period;
    }

    ScheduledFutureTask(
            EventExecutor executor, Queue<ScheduledFutureTask<?>> delayedTaskQueue,
            Callable<V> callable, long nanoTime) {

        super(executor, callable);
        this.delayedTaskQueue = delayedTaskQueue;
        deadlineNanos = nanoTime;
        periodNanos = 0;
    }

    @Override
    protected EventExecutor executor() {
        return super.executor();
    }

    public long deadlineNanos() {
        return deadlineNanos;
    }

    public long delayNanos() {
        return Math.max(0, deadlineNanos() - nanoTime());
    }

    public long delayNanos(long currentTimeNanos) {
        // currentTimeNanos - START_TIME 从类被用到到当前的耗时时间
        // deadlineNanos: System.nanoTime() - START_TIME+ delay 在将延时任务添加到队列中时计算得到的运行时刻（此时刻以START_TIME为起始时刻）
        // 起始时刻都是START_TIME，重新计算delay(因为当前方法是在任务被加到queue中后面执行的)
        // myConfusion :为啥要搞个START_TIME作为起始时刻呢？用系统的初始时刻不行吗
        return Math.max(0, deadlineNanos() - (currentTimeNanos - START_TIME));
    }

    @Override
    public long getDelay(TimeUnit unit) {
        return unit.convert(delayNanos(), TimeUnit.NANOSECONDS);
    }

    @Override
    public int compareTo(Delayed o) {
        if (this == o) {
            return 0;
        }

        ScheduledFutureTask<?> that = (ScheduledFutureTask<?>) o;
        long d = deadlineNanos() - that.deadlineNanos();
        if (d < 0) {
            return -1;
        } else if (d > 0) {
            return 1;
        } else if (id < that.id) {
            return -1;
        } else if (id == that.id) {
            throw new Error();
        } else {
            return 1;
        }
    }

    @Override
    public void run() {
        assert executor().inEventLoop();
        try {
            if (periodNanos == 0) {
                if (setUncancellableInternal()) {
                    V result = task.call();
                    setSuccessInternal(result);
                }
            } else {
                // check if is done as it may was cancelled
                if (!isCancelled()) {
                    task.call();
                    if (!executor().isShutdown()) {
                        long p = periodNanos;
                        if (p > 0) {
                            deadlineNanos += p;
                        } else {
                            deadlineNanos = nanoTime() - p;
                        }
                        if (!isCancelled()) {
                            delayedTaskQueue.add(this);
                        }
                    }
                }
            }
        } catch (Throwable cause) {
            setFailureInternal(cause);
        }
    }

    @Override
    protected StringBuilder toStringBuilder() {
        StringBuilder buf = super.toStringBuilder();
        buf.setCharAt(buf.length() - 1, ',');
        buf.append(" id: ");
        buf.append(id);
        buf.append(", deadline: ");
        buf.append(deadlineNanos);
        buf.append(", period: ");
        buf.append(periodNanos);
        buf.append(')');
        return buf;
    }
}
