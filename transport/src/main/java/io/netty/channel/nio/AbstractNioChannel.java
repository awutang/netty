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

import io.netty.channel.AbstractChannel;
import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelPromise;
import io.netty.channel.ConnectTimeoutException;
import io.netty.channel.EventLoop;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketAddress;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Abstract base class for {@link Channel} implementations which use a Selector based approach.
 */
public abstract class AbstractNioChannel extends AbstractChannel {

    private static final InternalLogger logger =
            InternalLoggerFactory.getInstance(AbstractNioChannel.class);

    // SelectableChannel是jdknio中SocketChannel与ServerSocketChannel的共同父类，
    // 因此可以给nettynio中的NioSocketChannel和NioServerSocketChannel公用
    private final SelectableChannel ch;
    // 代表jdknio中的SelectionKey.OP_READ
    protected final int readInterestOp;
    // channel注册到eventLoop后返回的选择键，因为可能会出现一个物理连接同时多个业务线程需要进行IO操作，因此多个业务线程共享一个channel实例，
    // 从而selectionKey也使共享的，因此selectionKey设置为volatile，可以达到可见性
    private volatile SelectionKey selectionKey;
    private volatile boolean inputShutdown;

    /**
     * The future of the current connection attempt.  If not null, subsequent
     * connection attempts will fail. 连接操作结果
     */
    private ChannelPromise connectPromise;
    // 连接超时定时器
    private ScheduledFuture<?> connectTimeoutFuture;
    private SocketAddress requestedRemoteAddress;

    /**
     * Create a new instance
     *
     * @param parent            the parent {@link Channel} by which this instance was created. May be {@code null}
     * @param ch                the underlying {@link SelectableChannel} on which it operates
     * @param readInterestOp    the ops to set to receive data from the {@link SelectableChannel}
     */
    protected AbstractNioChannel(Channel parent, EventLoop eventLoop, SelectableChannel ch, int readInterestOp) {
        super(parent, eventLoop);
        this.ch = ch;
        this.readInterestOp = readInterestOp;
        try {
            ch.configureBlocking(false);
        } catch (IOException e) {
            try {
                ch.close();
            } catch (IOException e2) {
                if (logger.isWarnEnabled()) {
                    logger.warn(
                            "Failed to close a partially initialized socket.", e2);
                }
            }

            throw new ChannelException("Failed to enter non-blocking mode.", e);
        }
    }

    @Override
    public boolean isOpen() {
        return ch.isOpen();
    }

    @Override
    public NioUnsafe unsafe() {
        return (NioUnsafe) super.unsafe();
    }

    protected SelectableChannel javaChannel() {
        return ch;
    }

    @Override
    public NioEventLoop eventLoop() {
        return (NioEventLoop) super.eventLoop();
    }

    /**
     * Return the current {@link SelectionKey}
     */
    protected SelectionKey selectionKey() {
        assert selectionKey != null;
        return selectionKey;
    }

    /**
     * Return {@code true} if the input of this {@link Channel} is shutdown
     */
    protected boolean isInputShutdown() {
        return inputShutdown;
    }

    /**
     * Shutdown the input of this {@link Channel}.
     */
    void setInputShutdown() {
        inputShutdown = true;
    }

    /**
     * Special {@link Unsafe} sub-type which allows to access the underlying {@link SelectableChannel}
     */
    public interface NioUnsafe extends Unsafe {
        /**
         * Return underlying {@link SelectableChannel}
         */
        SelectableChannel ch();

        /**
         * Finish connect
         */
        void finishConnect();

        /**
         * Read from underlying {@link SelectableChannel}
         */
        void read();

        void forceFlush();
    }

    protected abstract class AbstractNioUnsafe extends AbstractUnsafe implements NioUnsafe {

        @Override
        public SelectableChannel ch() {
            return javaChannel();
        }

        /**
         * 客户端channel发起连接
         * @param remoteAddress
         * @param localAddress
         * @param promise
         */
        @Override
        public void connect(
                final SocketAddress remoteAddress, final SocketAddress localAddress, final ChannelPromise promise) {
            // 1.判断channel是否打开，若未打开则无法发起连接
            if (!ensureOpen(promise)) {
                return;
            }

            try {
                if (connectPromise != null) {
                    throw new IllegalStateException("connection attempt already made");
                }

                // 2. 连接
                boolean wasActive = isActive();
                if (doConnect(remoteAddress, localAddress)) {
                    // 2.1 连接成功，触发channelActive事件
                    fulfillConnectPromise(promise, wasActive);
                } else {
                    // 2.2 若没有连接成功（正在等待服务端ack消息）
                    connectPromise = promise;
                    requestedRemoteAddress = remoteAddress;

                    // Schedule connect timeout.
                    int connectTimeoutMillis = config().getConnectTimeoutMillis();
                    if (connectTimeoutMillis > 0) {
                        // 2.2.1 连接超时任务
                        connectTimeoutFuture = eventLoop().schedule(new Runnable() {
                            @Override
                            public void run() {
                                ChannelPromise connectPromise = AbstractNioChannel.this.connectPromise;
                                ConnectTimeoutException cause =
                                        new ConnectTimeoutException("connection timed out: " + remoteAddress);
                                // connectPromise.tryFailure(cause)；用于判断超时后连接是否已经成功（因为成功时会设置promise.result=SUCCESS）
                                if (connectPromise != null && connectPromise.tryFailure(cause)) {
                                    // 若超时后连接不成功则关闭channel、释放outboundBuffer、取消channel注册
                                    close(voidPromise());
                                }
                            }
                        }, connectTimeoutMillis, TimeUnit.MILLISECONDS);
                    }

                    // 2.2.2 设置连接结果监听器。接收到连接完成通知时，先判断连接是否取消（chanel被取消了），若是则取消连接超时任务、关闭channel
                    promise.addListener(new ChannelFutureListener() {
                        /**
                         * 此方法在DefaultPromise.notifyListeners()中会被调用，观察者模式
                         * @param future  the source {@link Future} which called this callback
                         * @throws Exception
                         */
                        @Override
                        public void operationComplete(ChannelFuture future) throws Exception {
                            if (future.isCancelled()) {
                                if (connectTimeoutFuture != null) {
                                    connectTimeoutFuture.cancel(false);
                                }
                                connectPromise = null;
                                close(voidPromise());
                            }
                        }
                    });
                }
            } catch (Throwable t) {
                // 2.3 连接抛出异常
                if (t instanceof ConnectException) {
                    Throwable newT = new ConnectException(t.getMessage() + ": " + remoteAddress);
                    newT.setStackTrace(t.getStackTrace());
                    t = newT;
                }
                promise.tryFailure(t);
                closeIfClosed();
            }
        }

        /**
         * 将NioSocketChannel.selectionKey.interestOps设置为OP_READ，表示监听读事件，selector轮询读
         * @param promise
         * @param wasActive
         */
        private void fulfillConnectPromise(ChannelPromise promise, boolean wasActive) {
            // trySuccess() will return false if a user cancelled the connection attempt.
            boolean promiseSet = promise.trySuccess();

            // Regardless if the connection attempt was cancelled, channelActive() event should be triggered,
            // because what happened is what happened.
            if (!wasActive && isActive()) {
                // 若是之前channel并没有连接，则此次是首次连接，发起channelActive操作，
                // 将NioSocketChannel.selectionKey.interestOps设置为OP_READ，表示监听读事件，selector轮询读
                pipeline().fireChannelActive();
            }

            // If a user cancelled the connection attempt, close the channel, which is followed by channelInactive().
            if (!promiseSet) {
                close(voidPromise());
            }
        }

        /**
         * 客户端接收到服务服务端的tcp握手应答消息（三次握手中的第二次），通过SocketChannel.finishConnect对应答结果进行判断
         */
        @Override
        public void finishConnect() {
            // Note this method is invoked by the event loop only if the connection attempt was
            // neither cancelled nor timed out.

            // 1. 判断状态
            assert eventLoop().inEventLoop();
            assert connectPromise != null;

            try {
                // 2.缓存连接状态
                boolean wasActive = isActive();
                // 3. 判断应答结果
                doFinishConnect();
                // 3.1 代码执行到此处说明连接完成且成功
                fulfillConnectPromise(connectPromise, wasActive);
            } catch (Throwable t) {
                // 3.2 连接失败了
                if (t instanceof ConnectException) {
                    Throwable newT = new ConnectException(t.getMessage() + ": " + requestedRemoteAddress);
                    newT.setStackTrace(t.getStackTrace());
                    t = newT;
                }

                // Use tryFailure() instead of setFailure() to avoid the race against cancel().
                connectPromise.tryFailure(t);
                closeIfClosed();
            } finally {
                // Check for null as the connectTimeoutFuture is only created if a connectTimeoutMillis > 0 is used
                // See https://github.com/netty/netty/issues/1770
                if (connectTimeoutFuture != null) {
                    // 4. connectTimeoutFuture != null：connect()中设置了连接超时任务，
                    // 执行到此处说明要么到超时时间那么连接超时任务肯定执行了，myConfusion:若是没有到超时时间呢，不应该取消超时任务呐
                    connectTimeoutFuture.cancel(false);
                }
                connectPromise = null;
            }
        }

        @Override
        protected void flush0() {
            // Flush immediately only when there's no pending flush.
            // If there's a pending flush operation, event loop will call forceFlush() later,
            // and thus there's no need to call it now.
            if (isFlushPending()) {
                return;
            }
            super.flush0();
        }

        @Override
        public void forceFlush() {
            // directly call super.flush0() to force a flush now
            super.flush0();
        }

        private boolean isFlushPending() {
            SelectionKey selectionKey = selectionKey();
            return selectionKey.isValid() && (selectionKey.interestOps() & SelectionKey.OP_WRITE) != 0;
        }
    }

    @Override
    protected boolean isCompatible(EventLoop loop) {
        return loop instanceof NioEventLoop;
    }

    /**
     * 将当前channel对象注册到selector中
     * @throws Exception
     */
    @Override
    protected void doRegister() throws Exception {
        boolean selected = false;
        for (;;) {
            try {
                selectionKey = javaChannel().register(eventLoop().selector, 0, this);
                return;
            } catch (CancelledKeyException e) {
                // 如果之前已经试着删除了取消的selectionKey，但仍然报CancelledKeyException，则说明jdk nio代码有异常（channel.keys中的SelectionKey对象因bug没有删掉）
                if (!selected) {
                    // Force the Selector to select now as the "canceled" SelectionKey may still be
                    // cached and not removed because no Select.select(..) operation was called yet.
                    // 删除已经取消的selectionKey cancelledKeys
                    eventLoop().selectNow();
                    // 表示已经删除取消了的selectionKey
                    selected = true;
                } else {
                    // We forced a select operation on the selector before but the SelectionKey is still cached
                    // for whatever reason. JDK bug ?
                    throw e;
                }
            }
        }
    }

    @Override
    protected void doDeregister() throws Exception {
        eventLoop().cancel(selectionKey());
    }

    /**
     * 将待监听的网络事件设置为读操作
     * myConfusion:谁发起的呢？注册之后吗,是有一个线程会去监听Channel对象中是否有数据了即另一端的数据已经到达吗？
     * 按理应该是监听到了可读之后才发起真正的读，那为啥doBeginRead()是由DefaultChannelPipeline.read()触发的呢？虽然是由HeadHandler触发的，但是后面真正的读操作又是啥时候触发的呢？
     * @throws Exception
     */
    @Override
    protected void doBeginRead() throws Exception {
        if (inputShutdown) {
            return;
        }

        final SelectionKey selectionKey = this.selectionKey;
        if (!selectionKey.isValid()) {
            return;
        }

        final int interestOps = selectionKey.interestOps();
        // interestOps & readInterestOp) == 0表示selectionKey还没有设置读操作位
        if ((interestOps & readInterestOp) == 0) {
            selectionKey.interestOps(interestOps | readInterestOp);
        }
    }

    /**
     * Connect to the remote peer
     */
    protected abstract boolean doConnect(SocketAddress remoteAddress, SocketAddress localAddress) throws Exception;

    /**
     * Finish the connect
     */
    protected abstract void doFinishConnect() throws Exception;
}
