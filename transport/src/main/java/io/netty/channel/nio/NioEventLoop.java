/*
 * Copyright 2012 The Netty Project
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
package io.netty.channel.nio;


import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.EventLoopException;
import io.netty.channel.SingleThreadEventLoop;
import io.netty.channel.nio.AbstractNioChannel.NioUnsafe;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.spi.SelectorProvider;
import java.util.ArrayList;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link io.netty.channel.SingleThreadEventLoop} implementation which register the {@link Channel}'s to a
 * {@link Selector} and so does the multi-plexing of these in the event loop.
 *
 */
public final class NioEventLoop extends SingleThreadEventLoop {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(NioEventLoop.class);

    private static final int CLEANUP_INTERVAL = 256; // XXX Hard-coded value, but won't need customization.

    private static final boolean DISABLE_KEYSET_OPTIMIZATION =
            SystemPropertyUtil.getBoolean("io.netty.noKeySetOptimization", false);

    private static final int MIN_PREMATURE_SELECTOR_RETURNS = 3;
    private static final int SELECTOR_AUTO_REBUILD_THRESHOLD;

    // Workaround for JDK NIO bug.
    //
    // See:
    // - http://bugs.sun.com/view_bug.do?bug_id=6427854
    // - https://github.com/netty/netty/issues/203
    static {
        String key = "sun.nio.ch.bugLevel";
        try {
            String buglevel = System.getProperty(key);
            if (buglevel == null) {
                System.setProperty(key, "");
            }
        } catch (SecurityException e) {
            if (logger.isDebugEnabled()) {
                logger.debug("Unable to get/set System Property: {}", key, e);
            }
        }

        int selectorAutoRebuildThreshold = SystemPropertyUtil.getInt("io.netty.selectorAutoRebuildThreshold", 512);
        if (selectorAutoRebuildThreshold < MIN_PREMATURE_SELECTOR_RETURNS) {
            selectorAutoRebuildThreshold = 0;
        }

        SELECTOR_AUTO_REBUILD_THRESHOLD = selectorAutoRebuildThreshold;

        if (logger.isDebugEnabled()) {
            logger.debug("-Dio.netty.noKeySetOptimization: {}", DISABLE_KEYSET_OPTIMIZATION);
            logger.debug("-Dio.netty.selectorAutoRebuildThreshold: {}", SELECTOR_AUTO_REBUILD_THRESHOLD);
        }
    }

    /**
     * The NIO {@link Selector}. 多路复用器对象
     */
    Selector selector;
    private SelectedSelectionKeySet selectedKeys;

    private final SelectorProvider provider;

    /**
     * Boolean that controls determines if a blocked Selector.select should
     * break out of its selection process. In our case we use a timeout for
     * the select method and the select method will block for that time unless
     * waken up.
     *
     * 决定是否需要唤醒阻塞在select上的线程。超时或wakenUp为true都可以唤醒线程
     */
    private final AtomicBoolean wakenUp = new AtomicBoolean();
    private boolean oldWakenUp;

    // volatile:如果有一个线程T1正在run(),另一线程T2设置ioRatio,则T1能立马感知到变化
    private volatile int ioRatio = 50;
    private int cancelledKeys;
    private boolean needsToSelectAgain;

    NioEventLoop(NioEventLoopGroup parent, Executor executor, SelectorProvider selectorProvider) {
        super(parent, executor, false);
        if (selectorProvider == null) {
            throw new NullPointerException("selectorProvider");
        }
        provider = selectorProvider;
        // 初始化多路复用器，一个NioEventLoop新建也新建一个EPollSelectorImpl，因此NioEventLoop与EPollSelectorImp一一对应
        // 因此bossGroup,workerGroup中的每一个eventLoop都有一个不同的selector
        selector = openSelector();
    }

    /**
     * 初始化selector
     * @return
     */
    private Selector openSelector() {

        final Selector selector;
        try {
            // 1. 新建selector mac:new sun.nio.ch.KQueueSelectorProvider() Linux:EpollSelectorProvider()
            selector = provider.openSelector();
        } catch (IOException e) {
            throw new ChannelException("failed to open a new selector", e);
        }
        // 2.是否开启selectedKeySet的初始化，默认值是false,往下走，因此默认是开启的
        if (DISABLE_KEYSET_OPTIMIZATION) {
            return selector;
        }

        // 3. 优化：通过反射将1中生成的selector.selectedKeys/publicSelectedKeys替换成netty的SelectedSelectionKeySet对象
        try {
            SelectedSelectionKeySet selectedKeySet = new SelectedSelectionKeySet();
            // 反射
            Class<?> selectorImplClass =
                    Class.forName("sun.nio.ch.SelectorImpl", false, ClassLoader.getSystemClassLoader());

            // Ensure the current selector implementation is what we can instrument.
            if (!selectorImplClass.isAssignableFrom(selector.getClass())) {
                return selector;
            }

            // 获取selector属性并将属性设置为可写
            // private Set<SelectionKey> selectedKeys
            Field selectedKeysField = selectorImplClass.getDeclaredField("selectedKeys");
            // private Set<SelectionKey> publicSelectedKeys;
            Field publicSelectedKeysField = selectorImplClass.getDeclaredField("publicSelectedKeys");
            selectedKeysField.setAccessible(true);
            publicSelectedKeysField.setAccessible(true);

            // 设置selector.selectedKeys，将provider.openSelector()中设置的selector.selectedKeys/publicSelectedKeys替换了
            selectedKeysField.set(selector, selectedKeySet);
            publicSelectedKeysField.set(selector, selectedKeySet);

            // 优化后的selectionKeys,selector.selectedKeys与nioEventLoop.selectedKeys指向的是同一个对象
            selectedKeys = selectedKeySet;
            logger.trace("Instrumented an optimized java.util.Set into: {}", selector);
        } catch (Throwable t) {
            selectedKeys = null;
            logger.trace("Failed to instrument an optimized java.util.Set into: {}", selector, t);
        }

        return selector;
    }

    /**
     * taskQueue采用线程安全类ConcurrentLinkedQueue
     * @return
     */
    @Override
    protected Queue<Runnable> newTaskQueue() {
        // This event loop never calls takeTask()
        return new ConcurrentLinkedQueue<Runnable>();
    }

    /**
     * Registers an arbitrary {@link SelectableChannel}, not necessarily created by Netty, to the {@link Selector}
     * of this event loop.  Once the specified {@link SelectableChannel} is registered, the specified {@code task} will
     * be executed by this event loop when the {@link SelectableChannel} is ready.
     */
    public void register(final SelectableChannel ch, final int interestOps, final NioTask<?> task) {
        if (ch == null) {
            throw new NullPointerException("ch");
        }
        if (interestOps == 0) {
            throw new IllegalArgumentException("interestOps must be non-zero.");
        }
        if ((interestOps & ~ch.validOps()) != 0) {
            throw new IllegalArgumentException(
                    "invalid interestOps: " + interestOps + "(validOps: " + ch.validOps() + ')');
        }
        if (task == null) {
            throw new NullPointerException("task");
        }

        if (isShutdown()) {
            throw new IllegalStateException("event loop shut down");
        }

        try {
            ch.register(selector, interestOps, task);
        } catch (Exception e) {
            throw new EventLoopException("failed to register a channel", e);
        }
    }

    /**
     * Returns the percentage of the desired amount of time spent for I/O in the event loop.
     */
    public int getIoRatio() {
        return ioRatio;
    }

    /**
     * Sets the percentage of the desired amount of time spent for I/O in the event loop.  The default value is
     * {@code 50}, which means the event loop will try to spend the same amount of time for I/O as for non-I/O tasks.
     *
     * myConfusion:如果有多个线程调用此方法，那岂不是有线程安全的问题（因为ioRation是volatile,volatile不能保证赋值操作的原子性）--是因为
     * 是外部应用来设置此ioRatio的（一般只有一个用户线程，NioEventLoop中没有设置该字段）
     */
    public void setIoRatio(int ioRatio) {
        if (ioRatio <= 0 || ioRatio >= 100) {
            throw new IllegalArgumentException("ioRatio: " + ioRatio + " (expected: 0 < ioRatio < 100)");
        }
        this.ioRatio = ioRatio;
    }

    /**
     * Replaces the current {@link Selector} of this event loop with newly created {@link Selector}s to work
     * around the infamous epoll 100% CPU bug.
     */
    public void rebuildSelector() {
        if (!inEventLoop()) {
            // 若不是当前线程发起的rebuildSelector，则需要将此操作封装为Task,放入到taskQueue中，以后当前这个线程会执行到
            // 这样就将多个线程并发执行，转成了当前一个线程做多个操作，避免了多线程并发安全问题
            execute(new Runnable() {
                @Override
                public void run() {
                    rebuildSelector();
                }
            });
            return;
        }

        final Selector oldSelector = selector;
        final Selector newSelector;

        if (oldSelector == null) {
            return;
        }

        try {
            // 1. 通过方法openSelector创建一个新的selector。
            newSelector = openSelector();
        } catch (Exception e) {
            logger.warn("Failed to create a new Selector.", e);
            return;
        }


        // 将之前旧selector上的SocketChannel都转移到新的selector,并关闭旧的selector
        // Register all channels to the new Selector.
        int nChannels = 0;
        for (;;) {
            try {
                for (SelectionKey key: oldSelector.keys()) {
                    Object a = key.attachment();
                    try {
                        if (key.channel().keyFor(newSelector) != null) {
                            continue;
                        }

                        int interestOps = key.interestOps();
                        // 2. 将old selector的selectionKey执行cancel。
                        key.cancel();
                        // 3、将old selector的channel重新注册到新的selector中。
                        key.channel().register(newSelector, interestOps, a);
                        nChannels ++;
                    } catch (Exception e) {
                        logger.warn("Failed to re-register a Channel to the new Selector.", e);
                        if (a instanceof AbstractNioChannel) {
                            AbstractNioChannel ch = (AbstractNioChannel) a;
                            ch.unsafe().close(ch.unsafe().voidPromise());
                        } else {
                            @SuppressWarnings("unchecked")
                            NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                            invokeChannelUnregistered(task, key, e);
                        }
                    }
                }
            } catch (ConcurrentModificationException e) {
                // Probably due to concurrent modification of the key set.
                continue;
            }

            break;
        }

        selector = newSelector;

        try {
            // time to close the old selector as everything else is registered to the new one
            oldSelector.close();
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("Failed to close the old Selector.", t);
            }
        }

        logger.info("Migrated " + nChannels + " channel(s) to the new Selector.");
    }

    /**
     *
     * netty中最核心的方法，没有之一!!!
     *
     * NIO线程执行的操作，从哪触发的？--SingleThreadEventExecutor.this.run();
     *
     * myConfusion:1.run()是服务启动时就触发然后一直循环执行的吗？--SeverBootStrap().doBind()时触发run()然后一直循环执行
     * 2.不同的网络事件比如OP_READ之间的流转是如何进行的？比如OP_ACCEPT时监听连接事件，连接事件成功之后就改成OP_READ用于监听读操作
     * 3.连接操作源码从头到尾线状梳理下
     *
     *
     *
     * NioEventLoop中维护了一个线程，线程启动时会调用NioEventLoop的run方法，轮询IO任务、执行I/O任务和非I/O任务：
     * I/O任务:即selectionKey中ready的事件，如accept、connect、read、write等，由processSelectedKeys方法触发。
     * 非IO任务:添加到taskQueue中的任务（包括后续从scheduledTaskQueue中移过去的），如register0、bind0等任务，由runAllTasks方法触发。
     * 两种任务的执行时间比由变量ioRatio控制，默认为50，则表示允许非IO任务执行的时间与IO任务的执行时间相等
     *
     * select()  检查是否有IO事件
     * ProcessorSelectedKeys()    处理IO事件
     * RunAllTask()    处理异步任务队列
     */
    @Override
    protected void run() {
        // 1. 循环执行，直至接收到退出指令
        for (;;) {
            // 2. 将wakenUp还原为false,并将之前的wakenUp值保存到oldWakenUp--可以用来判断是否需要从NioEventLoop.select()中退出
            oldWakenUp = wakenUp.getAndSet(false);
            try {
                // myConfusionsv:消息队列中有任务说明是有用户线程需要进行IO操作，为啥要立马去查询channel中有ready的呢？难道是用户线程ready好的(要么准备好了数据写出，要么希望读取到数据)
                // --taskQueue中是非IO任务，比如register0,是需要立马执行的
                // 3. 判断taskQueue中是否有任务
                if (hasTasks()) {
                    // 3.1 有，则执行selectNow()--因为taskQueue中的是非IO任务，因此如果现在已经有了非IO任务的话，那就需要快速执行IO任务（因此调用selectNow()实现快速返回准备好的event）
                    selectNow();
                } else {
                    // 3.2 无，可以在epoll_wait上等待一段时间
                    // 与selectNow的区别？不是立即开始查询ready的channel的
                    select();

                    // 'wakenUp.compareAndSet(false, true)' is always evaluated
                    // before calling 'selector.wakeup()' to reduce the wake-up
                    // overhead. (Selector.wakeup() is an expensive operation.)
                    //
                    // However, there is a race condition in this approach.
                    // The race condition is triggered when 'wakenUp' is set to
                    // true too early.
                    //
                    // 'wakenUp' is set to true too early if:
                    // 1) Selector is waken up between 'wakenUp.set(false)' and
                    //    'selector.select(...)'. (BAD)
                    // 2) Selector is waken up between 'selector.select(...)' and
                    //    'if (wakenUp.get()) { ... }'. (OK)
                    //
                    // In the first case, 'wakenUp' is set to true and the
                    // following 'selector.select(...)' will wake up immediately.
                    // Until 'wakenUp' is set to false again in the next round,
                    // 'wakenUp.compareAndSet(false, true)' will fail, and therefore
                    // any attempt to wake up the Selector will fail, too, causing
                    // the following 'selector.select(...)' call to block
                    // unnecessarily.
                    //
                    // To fix this problem, we wake up the selector again if wakenUp
                    // is true immediately after selector.select(...).
                    // It is inefficient in that it wakes up the selector for both
                    // the first case (BAD - wake-up required) and the second case
                    // (OK - no wake-up required).
                    // myConfusionsv:wakeUp上面注释解释的作用还不是太懂，wakeUp与selector.select()到底有晒关联？--wakeUp()可以将阻塞在selector.select()的线程中断
                    if (wakenUp.get()) {
                        /**wakenUp是AtomicBoolean类型的变量，如果是true，则表示最近调用过wakeup方法，如果是false，
                         * 则表示最近未调用wakeup方法，另外每次进入select(boolean)方法都会将wakenUp置为false。
                         * 而wakeup方法是针对selector.select方法设计的，如果调用wakeup方法时处于selector.select阻塞方法中，
                         * 则会直接唤醒处于selector.select阻塞中的线程，而如果调用wakeup方法时selector不处于selector.select阻塞方法中，
                         * 则效果是在下一次调selector.select方法时不阻塞（有点像LockSupport.park/unpark的效果）*/
                        selector.wakeup();
                    }
                }

                // 干啥的？
                cancelledKeys = 0;

                final long ioStartTime = System.nanoTime();
                needsToSelectAgain = false;

                // 4. 用来处理准备好的selectionKey(select()返回的)
                // select() selectNow() 会将ready的结果更新到selectedKeys
                // 为啥selectedKeys != null就是开启了优化功能？因为即使不开启优化selectedKeys也是初始化成一个Set实例的
                // --看错啦，当前selectedKeys是nioEventLoop属性而不是selector.selectedKeys
                if (selectedKeys != null) {
                    // selectedKeys.flip()返回SelectedSelectionKeySet.SelectionKey[](这个数组中保存的正是epoll_wait中查询到ready的fd对应的selectionKey)
                    processSelectedKeysOptimized(selectedKeys.flip());
                } else {
                    processSelectedKeysPlain(selector.selectedKeys());
                }

                // 5. 完成上面的IO操作后，开始执行非IO操作：系统Task与定时任务.
                final long ioTime = System.nanoTime() - ioStartTime;

                // ioRatio：分配执行时间 io与非IO操作的，保证两者都充分利用CPU时间
                // (100 - ioRatio) / ioRatio：非IO操作执行时间占IO操作的比例，所以ioTime * (100 - ioRatio) / ioRatio是执行非IO操作的执行时间
                final int ioRatio = this.ioRatio;
                runAllTasks(ioTime * (100 - ioRatio) / ioRatio);

                // 6.
                if (isShuttingDown()) {
                    // close所有channel
                    closeAll();
                    if (confirmShutdown()) {
                        // 确定关闭后退出IO线程循环
                        break;
                    }
                }
            } catch (Throwable t) {
                logger.warn("Unexpected exception in the selector loop.", t);

                // Prevent possible consecutive immediate failures that lead to
                // excessive CPU consumption.
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    // Ignore.
                }
            }
        }
    }

    @Override
    protected void cleanup() {
        try {
            selector.close();
        } catch (IOException e) {
            logger.warn("Failed to close a selector.", e);
        }
    }

    void cancel(SelectionKey key) {
        key.cancel();
        cancelledKeys ++;
        if (cancelledKeys >= CLEANUP_INTERVAL) {
            cancelledKeys = 0;
            needsToSelectAgain = true;
        }
    }

    @Override
    protected Runnable pollTask() {
        Runnable task = super.pollTask();
        if (needsToSelectAgain) {
            selectAgain();
        }
        return task;
    }

    private void processSelectedKeysPlain(Set<SelectionKey> selectedKeys) {
        // check if the set is empty and if so just return to not create garbage by
        // creating a new Iterator every time even if there is nothing to process.
        // See https://github.com/netty/netty/issues/597
        if (selectedKeys.isEmpty()) {
            return;
        }

        Iterator<SelectionKey> i = selectedKeys.iterator();
        for (;;) {
            final SelectionKey k = i.next();
            final Object a = k.attachment();
            i.remove();

            if (a instanceof AbstractNioChannel) {
                // 如果a是AbstractNioChannel，则说明是NioServerSocketChannel或NioSocketChannel，需要进行IO操作
                processSelectedKey(k, (AbstractNioChannel) a);
            } else {
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }

            if (!i.hasNext()) {
                break;
            }

            if (needsToSelectAgain) {
                selectAgain();
                selectedKeys = selector.selectedKeys();

                // Create the iterator again to avoid ConcurrentModificationException
                if (selectedKeys.isEmpty()) {
                    break;
                } else {
                    i = selectedKeys.iterator();
                }
            }
        }
    }

    /**
     * 处理有事件发生(其实就是准备好的fd)的selectkey
     * @param selectedKeys
     */
    private void processSelectedKeysOptimized(SelectionKey[] selectedKeys) {
        for (int i = 0;; i ++) {
            final SelectionKey k = selectedKeys[i];
            if (k == null) {
                break;
            }

            // 此处attachment是channel注册到selector时保存的channel对象
            final Object a = k.attachment();

            if (a instanceof AbstractNioChannel) {
                processSelectedKey(k, (AbstractNioChannel) a);
            } else {
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }

            if (needsToSelectAgain) {
                selectAgain();
                // Need to flip the optimized selectedKeys to get the right reference to the array
                // and reset the index to -1 which will then set to 0 on the for loop
                // to start over again.
                //
                // See https://github.com/netty/netty/issues/1523
                selectedKeys = this.selectedKeys.flip();
                i = -1;
            }
        }
    }

    /**
     * 对单一事件进行处理
     * 监听事件流转：
     *  服务端：
     *  NioServerSocketChannel注册+绑定好之后,interestOp添加OP_ACCEPT->epoll_wait监听到accept ready则在NioEventLoop.run()中触发
     *  服务端accept()->accept成功之后创建NioSocketChannel实例并触发ServerBootstrapAcceptor.channelRead()->NioSocketChannel注册,
     *  注册成功后添加OP_READ(NioServerSocketChannel的监听事件仍设置为ACCEPT,监听客户端来的连接（其实就是boss线程组负责接收连接）)
     *  ->监听到读事件并读完后OP_READ(表明接下来仍旧监控是否读ready)->业务代码中触发flush0,当发生写半包时interestOp添加OP_WRITE(myConfusionsv:啥时候将OP_Write添加到监听事件中的？
     *  --往channel中写数据发生写半包时，用于监听之后何时可以往tcp缓冲区写数据,监听到可写后NioEventLoop.run()中flush0,其他情况的flush0是由handler触发的，
     *  因此应该是自定义handler的逻辑,这是用户主动触发的而不是监听行为导致的)->写完后（写半包解决了）,interestOp去除了OP_WRITE标记
     *
     *  客户端：
     *  NioSocketChannel发起连接（OP_CONNECT会在interestOP中吗即epoll监听可连接？--如果发起的连接还未收到服务端ack则将interestOp
     *  设置为OP_CONNECT,用于监听服务端ack）->监听到连接，发起finishConnect()->连接成功后interestOp添加OP_READ,接下来操作跟服务端一样了
     *
     * write()是由业务代码触发的（flush()由NioEventLoop线程触发（也可能业务代码自己实现了）），read()由NioEventLoop线程触发
     *
     * @param k
     * @param ch
     */
    private static void processSelectedKey(SelectionKey k, AbstractNioChannel ch) {
        final NioUnsafe unsafe = ch.unsafe();
        // 1. 判断当前selectionKey是否可用
        if (!k.isValid()) {
            // close the channel if the key is not valid anymore
            unsafe.close(unsafe.voidPromise());
            return;
        }

        try {
            // 2. 获取ready event
            int readyOps = k.readyOps();

            // 2.1 若准备好的操作是read或 accept
            // Also check for readOps of 0 to workaround possible JDK bug which may otherwise lead
            // to a spin loop
            if ((readyOps & (SelectionKey.OP_READ | SelectionKey.OP_ACCEPT)) != 0 || readyOps == 0) {
                // readyOps & (SelectionKey.OP_READ | SelectionKey.OP_ACCEPT)) != 0:判断是read或accept操作
                // myConfusion:readyOps == 0是什么场景？初始化的？为啥可以去做read呢？spin loop?
                // unsafe实现是多态的：如果unsafe外层实例是NioServerSocketChannel,则是accept操作；若是NioSocketChannel则去read
                unsafe.read();
                if (!ch.isOpen()) {
                    // Connection already closed - no need to handle write.
                    return;
                }
            }
            // 2.2 若准备好的操作是write,向channel写入数据
            if ((readyOps & SelectionKey.OP_WRITE) != 0) {
                // Call forceFlush which will also take care of clear the OP_WRITE once there is nothing left to write
                ch.unsafe().forceFlush();
            }
            // 2.3 若准备好的操作是connect
            if ((readyOps & SelectionKey.OP_CONNECT) != 0) {
                // remove OP_CONNECT as otherwise Selector.select(..) will always return without blocking
                // See https://github.com/netty/netty/issues/924

                // 对网络操作位进行修改,将SelectionKey.OP_CONNECT注销
                int ops = k.interestOps();
                ops &= ~SelectionKey.OP_CONNECT;

                // 底层其实更新了epoll中fd监听事件
                k.interestOps(ops);

                // 客户端连接
                unsafe.finishConnect();
            }
        } catch (CancelledKeyException e) {
            unsafe.close(unsafe.voidPromise());
        }
    }

    private static void processSelectedKey(SelectionKey k, NioTask<SelectableChannel> task) {
        int state = 0;
        try {
            task.channelReady(k.channel(), k);
            state = 1;
        } catch (Exception e) {
            k.cancel();
            invokeChannelUnregistered(task, k, e);
            state = 2;
        } finally {
            switch (state) {
            case 0:
                k.cancel();
                invokeChannelUnregistered(task, k, null);
                break;
            case 1:
                if (!k.isValid()) { // Cancelled by channelReady()
                    invokeChannelUnregistered(task, k, null);
                }
                break;
            }
        }
    }

    private void closeAll() {
        // 重新获取所有ready的channel
        selectAgain();
        Set<SelectionKey> keys = selector.keys();

        Collection<AbstractNioChannel> channels = new ArrayList<AbstractNioChannel>(keys.size());
        for (SelectionKey k: keys) {
            Object a = k.attachment();
            if (a instanceof AbstractNioChannel) {
                channels.add((AbstractNioChannel) a);
            } else {
                k.cancel();
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                invokeChannelUnregistered(task, k, null);
            }
        }

        // 关闭所有ready的channel
        for (AbstractNioChannel ch: channels) {
            ch.unsafe().close(ch.unsafe().voidPromise());
        }
    }

    private static void invokeChannelUnregistered(NioTask<SelectableChannel> task, SelectionKey k, Throwable cause) {
        try {
            task.channelUnregistered(k.channel(), cause);
        } catch (Exception e) {
            logger.warn("Unexpected exception while running NioTask.channelUnregistered()", e);
        }
    }

    @Override
    protected void wakeup(boolean inEventLoop) {
        if (!inEventLoop && wakenUp.compareAndSet(false, true)) {
            selector.wakeup();
        }
    }

    void selectNow() throws IOException {
        try {
            // 非阻塞，立马返回的 myConfusionsv:这里的非阻塞指的是nio底层epoll吗？可是epoll其实是阻塞的--因为selectNow()的timeOut为0，所以不会等待，是epoll但不设置超时时间
            //  Invoking this method clears the effect of any previous invocations
            //     * of the {@link #wakeup wakeup} method.
            // Add the ready keys to the selected key set.
            /**
             * if (pollWrapper.interrupted()) {
             *             // Clear the wakeup pipe
             *             pollWrapper.putEventOps(pollWrapper.interruptedIndex(), 0);
             *             synchronized (interruptLock) {
             *                 pollWrapper.clearInterrupted();
             *                 IOUtil.drain(fd0);
             *                 interruptTriggered = false;
             *             }
             *         }
             */
            selector.selectNow();
        } finally {
            // restore wakup state if needed myConfusionsv:这个wakenUp用来干啥的？--可以中断epoll_wait阻塞
            if (wakenUp.get()) {
                /**
                 Causes the first selection operation that has not yet returned to return
                 * immediately.
                 *
                 * <p> If another thread is currently blocked in an invocation of the
                 * {@link #select()} or {@link #select(long)} methods then that invocation
                 * will return immediately.  If no selection operation is currently in
                 * progress then the next invocation of one of these methods will return
                 * immediately unless the {@link #selectNow()} method is invoked in the
                 * meantime（同时）.  In any case the value returned by that invocation may be
                 * non-zero.  Subsequent invocations of the {@link #select()} or {@link
                 * #select(long)} methods will block as usual unless this method is invoked
                 * again in the meantime.
                 *
                 * if (!interruptTriggered) {
                 *                 pollWrapper.interrupt();
                 *                 interruptTriggered = true;
                 *             }
                 *             所以wakeup()置为中断，select()清除中断
                 *             pollWrapper.interrupt()底层native方法interrupt()应该是可以中断epoll阻塞**/
                selector.wakeup();
            }
        }
    }

    /**
     *
     * @throws IOException
     */
    private void select() throws IOException {
        Selector selector = this.selector;
        try {
            // selectCnt 用来记录selector.select方法的执行次数和标识是否执行过selector.selectNow()
            int selectCnt = 0;
            long currentTimeNanos = System.nanoTime();
            // 1. delayNanos(currentTimeNanos):如果有定时任务要处理则返回延迟任务队列中第一个任务的剩余延时时间，否则返回1s(以纳秒单位返回)
            // myConfusionsv:delayedTaskQueue与taskQueue有关系吗？--delayedTaskQueue中的任务到执行时间后放入taskQueue中
            long selectDeadLineNanos = currentTimeNanos + delayNanos(currentTimeNanos);
            for (;;) {
                // 2. 为延时时间增加0.5ms的调整值
                long timeoutMillis = (selectDeadLineNanos - currentTimeNanos + 500000L) / 1000000L;
                // 3. 如果延迟任务队列中第一个任务的最晚还能延迟执行的时间小于500000纳秒，且selectCnt == 0，则执行selector.selectNow()方法并立即返回。
                if (timeoutMillis <= 0) {
                    if (selectCnt == 0) {
                        selector.selectNow();
                        selectCnt = 1;
                    }
                    break;
                }

                // 4. 最多阻塞timeoutMillis返回select结果（myConfusionsv:此处执行的select操作应该与延时任务没关系吧？
                // --是的，select操作底层是epoll_wait,返回准备好的fd）--最多阻塞timeoutMillis是因为timeoutMillis之后延时任务需要被执行，所以不能浪费时间在epoll_wait上
                int selectedKeys = selector.select(timeoutMillis);
                // 记录select次数
                selectCnt ++;

                // 5. 如果已经存在ready的selectionKey，或者selector被唤醒，或者taskQueue不为空则退出循环。
                // wakeUp用来描述 a blocked Selector.select should break out of its selection process
                if (selectedKeys != 0 || oldWakenUp || wakenUp.get() || hasTasks()) {
                    // Selected something,--有读或写操作准备好了，接下来需要应用执行读或写操作，因此需要退出
                    // waken up by user, or --oldWakenUp（当前的操作是否需要唤醒） wakenUp.get()为true（可能被外部线程唤醒），
                    // select阻塞被中断，为啥要退出呢？是因为当前也是select方法（虽然不是底层epoll）既然用户想快速返回那么就应该从循环中退出？
                    // the task queue has a pending task.--有任务需要执行，因此需要退出
                    break;
                }

                // 6.代码走到此处（没有在5退出循环）可能是发生了jdk epoll bug，因此如下就是在判断若发生了epoll bug则rebuild selector
                if (SELECTOR_AUTO_REBUILD_THRESHOLD > 0 &&
                        selectCnt >= SELECTOR_AUTO_REBUILD_THRESHOLD) {
                    // selectCnt超过SELECTOR_AUTO_REBUILD_THRESHOLD则说明发生了epoll死循环即jdk epoll bug

                    // The selector returned prematurely many times in a row.
                    // Rebuild the selector to work around the problem.
                    logger.warn(
                            "Selector.select() returned prematurely {} times in a row; rebuilding selector.",
                            selectCnt);

                    // 6.1 检测到了jdk epoll bug（selector.select()处于死循环)，则重建selector实例
                    rebuildSelector();
                    selector = this.selector;

                    // 6.2 Select again to populate selectedKeys.(意思是检查是否有ready的fd)
                    selector.selectNow();
                    selectCnt = 1;
                    break;
                }

                // 7. 为下一次循环做准备
                currentTimeNanos = System.nanoTime();
            }

            if (selectCnt > MIN_PREMATURE_SELECTOR_RETURNS) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Selector.select() returned prematurely {} times in a row.", selectCnt - 1);
                }
            }
        } catch (CancelledKeyException e) {
            if (logger.isDebugEnabled()) {
                logger.debug(CancelledKeyException.class.getSimpleName() + " raised by a Selector - JDK bug?", e);
            }
            // Harmless exception - log anyway
        }
    }

    private void selectAgain() {
        needsToSelectAgain = false;
        try {
            selector.selectNow();
        } catch (Throwable t) {
            logger.warn("Failed to update SelectionKeys.", t);
        }
    }
}
