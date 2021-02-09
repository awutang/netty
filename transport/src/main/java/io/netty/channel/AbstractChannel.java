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
package io.netty.channel;

import io.netty.buffer.ByteBufAllocator;
import io.netty.util.DefaultAttributeMap;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.EmptyArrays;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NotYetConnectedException;

/**
 * A skeletal {@link Channel} implementation.
 */
public abstract class AbstractChannel extends DefaultAttributeMap implements Channel {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(AbstractChannel.class);

    static final ClosedChannelException CLOSED_CHANNEL_EXCEPTION = new ClosedChannelException();
    static final NotYetConnectedException NOT_YET_CONNECTED_EXCEPTION = new NotYetConnectedException();

    /**
     * 将如下两个异常的堆栈设置为空的StackTraceElement，为啥勒？难道是因为这两类异常不需要看具体堆栈信息只需要看ClassName就可以判断出异常了？
     * -- 可是若没有StackTraceElement，className也无法展示吧-可以打印className,CLOSED_CHANNEL_EXCEPTION.printStackTrace()会打印"java.nio.channels.ClosedChannelException"
     */
    static {
        CLOSED_CHANNEL_EXCEPTION.setStackTrace(EmptyArrays.EMPTY_STACK_TRACE);
        NOT_YET_CONNECTED_EXCEPTION.setStackTrace(EmptyArrays.EMPTY_STACK_TRACE);
    }

    public static void main(String[] args) {
        CLOSED_CHANNEL_EXCEPTION.printStackTrace();
    }

    // 预测下一个报文的大小，基于之前采样的数据进行预测
    private MessageSizeEstimator.Handle estimatorHandle;

    // 聚合了Channel需要使用到的所有能力对象
    // 父类Channel
    private final Channel parent;
    // globally unique identifier of this {@link Channel}
    private final ChannelId id = DefaultChannelId.newInstance();
    private final Unsafe unsafe;
    // 当前channel对象对应的pipeline
    private final DefaultChannelPipeline pipeline;
    private final ChannelFuture succeededFuture = new SucceededChannelFuture(this, null);
    private final VoidChannelPromise voidPromise = new VoidChannelPromise(this, true);
    private final VoidChannelPromise unsafeVoidPromise = new VoidChannelPromise(this, false);
    private final CloseFuture closeFuture = new CloseFuture(this);

    private volatile SocketAddress localAddress;
    private volatile SocketAddress remoteAddress;

    // 构造当前channel对象时绑定的eventLoop,在注册时会注册到此eventLoop
    private final EventLoop eventLoop;
    // 标记该channel是否已注册,所以netty的channel只会注册到一个selector中
    private volatile boolean registered;

    /** Cache for the string representation of this channel */
    private boolean strValActive;
    private String strVal;

    /**
     * Creates a new instance.
     *
     * @param parent
     *        the parent of this channel. {@code null} if there's no parent.
     */
    protected AbstractChannel(Channel parent, EventLoop eventLoop) {
        this.parent = parent;
        this.eventLoop = validate(eventLoop);
        unsafe = newUnsafe();
        pipeline = new DefaultChannelPipeline(this);
    }

    @Override
    public final ChannelId id() {
        return id;
    }

    @Override
    public boolean isWritable() {
        ChannelOutboundBuffer buf = unsafe.outboundBuffer();
        return buf != null && buf.getWritable();
    }

    @Override
    public Channel parent() {
        return parent;
    }

    @Override
    public ChannelPipeline pipeline() {
        return pipeline;
    }

    @Override
    public ByteBufAllocator alloc() {
        return config().getAllocator();
    }

    @Override
    public EventLoop eventLoop() {
        return eventLoop;
    }

    @Override
    public SocketAddress localAddress() {
        SocketAddress localAddress = this.localAddress;
        if (localAddress == null) {
            try {
                this.localAddress = localAddress = unsafe().localAddress();
            } catch (Throwable t) {
                // Sometimes fails on a closed socket in Windows.
                return null;
            }
        }
        return localAddress;
    }

    protected void invalidateLocalAddress() {
        localAddress = null;
    }

    @Override
    public SocketAddress remoteAddress() {
        SocketAddress remoteAddress = this.remoteAddress;
        if (remoteAddress == null) {
            try {
                this.remoteAddress = remoteAddress = unsafe().remoteAddress();
            } catch (Throwable t) {
                // Sometimes fails on a closed socket in Windows.
                return null;
            }
        }
        return remoteAddress;
    }

    /**
     * Reset the stored remoteAddress
     */
    protected void invalidateRemoteAddress() {
        remoteAddress = null;
    }

    @Override
    public boolean isRegistered() {
        return registered;
    }

    @Override
    public ChannelFuture bind(SocketAddress localAddress) {
        return pipeline.bind(localAddress);
    }

    /**
     * 当客户端发起连接时，将这个连接事件分发给bossGroup,group中分配一个线程处理这个连接操作
     * 一个连接操作可能包括若干个逻辑，将这些逻辑在不同的channelHandler中实现，需要处理的逻辑则进行拦截与处理，不关心的逻辑直接过滤。
     * 将整个操作拆分成多个逻辑，便于功能的扩展
     * 这是职责链模式 myConfusionsv:AbstractChannel.connect()的职责链体现在哪？
     * --其实是由当前handler决定的，如果决定向下传播，则当前handler中会再次执行ctx.connect(SocketAddress remoteAddress)
     * @param remoteAddress
     * @return
     */
    @Override
    public ChannelFuture connect(SocketAddress remoteAddress) {
        return pipeline.connect(remoteAddress);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress) {
        return pipeline.connect(remoteAddress, localAddress);
    }

    @Override
    public ChannelFuture disconnect() {
        return pipeline.disconnect();
    }

    @Override
    public ChannelFuture close() {
        return pipeline.close();
    }

    @Override
    public Channel flush() {
        pipeline.flush();
        return this;
    }

    @Override
    public ChannelFuture bind(SocketAddress localAddress, ChannelPromise promise) {
        return pipeline.bind(localAddress, promise);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, ChannelPromise promise) {
        return pipeline.connect(remoteAddress, promise);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
        return pipeline.connect(remoteAddress, localAddress, promise);
    }

    @Override
    public ChannelFuture disconnect(ChannelPromise promise) {
        return pipeline.disconnect(promise);
    }

    @Override
    public ChannelFuture close(ChannelPromise promise) {
        return pipeline.close(promise);
    }

    /**
     * 同样职责链
     * @return
     */
    @Override
    public Channel read() {
        pipeline.read();
        return this;
    }

    @Override
    public ChannelFuture write(Object msg) {
        return pipeline.write(msg);
    }

    @Override
    public ChannelFuture write(Object msg, ChannelPromise promise) {
        return pipeline.write(msg, promise);
    }

    @Override
    public ChannelFuture writeAndFlush(Object msg) {
        return pipeline.writeAndFlush(msg);
    }

    @Override
    public ChannelFuture writeAndFlush(Object msg, ChannelPromise promise) {
        return pipeline.writeAndFlush(msg, promise);
    }

    @Override
    public ChannelPromise newPromise() {
        return new DefaultChannelPromise(this);
    }

    @Override
    public ChannelProgressivePromise newProgressivePromise() {
        return new DefaultChannelProgressivePromise(this);
    }

    @Override
    public ChannelFuture newSucceededFuture() {
        return succeededFuture;
    }

    @Override
    public ChannelFuture newFailedFuture(Throwable cause) {
        return new FailedChannelFuture(this, null, cause);
    }

    @Override
    public ChannelFuture closeFuture() {
        return closeFuture;
    }

    @Override
    public Unsafe unsafe() {
        return unsafe;
    }

    /**
     * Create a new {@link AbstractUnsafe} instance which will be used for the life-time of the {@link Channel}
     */
    protected abstract AbstractUnsafe newUnsafe();

    /**
     * Returns the ID of this channel.
     */
    @Override
    public final int hashCode() {
        return id.hashCode();
    }

    /**
     * Returns {@code true} if and only if the specified object is identical
     * with this channel (i.e: {@code this == o}).
     */
    @Override
    public final boolean equals(Object o) {
        return this == o;
    }

    @Override
    public final int compareTo(Channel o) {
        if (this == o) {
            return 0;
        }

        return id().compareTo(o.id());
    }

    /**
     * Returns the {@link String} representation of this channel.  The returned
     * string contains the {@linkplain #hashCode()} ID}, {@linkplain #localAddress() local address},
     * and {@linkplain #remoteAddress() remote address} of this channel for
     * easier identification.
     */
    @Override
    public String toString() {
        boolean active = isActive();
        if (strValActive == active && strVal != null) {
            return strVal;
        }

        SocketAddress remoteAddr = remoteAddress();
        SocketAddress localAddr = localAddress();
        if (remoteAddr != null) {
            SocketAddress srcAddr;
            SocketAddress dstAddr;
            if (parent == null) {
                srcAddr = localAddr;
                dstAddr = remoteAddr;
            } else {
                srcAddr = remoteAddr;
                dstAddr = localAddr;
            }

            StringBuilder buf = new StringBuilder(96);
            buf.append("[id: 0x");
            buf.append(id.asShortText());
            buf.append(", ");
            buf.append(srcAddr);
            buf.append(active? " => " : " :> ");
            buf.append(dstAddr);
            buf.append(']');
            strVal = buf.toString();
        } else if (localAddr != null) {
            StringBuilder buf = new StringBuilder(64);
            buf.append("[id: 0x");
            buf.append(id.asShortText());
            buf.append(", ");
            buf.append(localAddr);
            buf.append(']');
            strVal = buf.toString();
        } else {
            StringBuilder buf = new StringBuilder(16);
            buf.append("[id: 0x");
            buf.append(id.asShortText());
            buf.append(']');
            strVal = buf.toString();
        }

        strValActive = active;
        return strVal;
    }

    @Override
    public final ChannelPromise voidPromise() {
        return voidPromise;
    }

    final MessageSizeEstimator.Handle estimatorHandle() {
        if (estimatorHandle == null) {
            estimatorHandle = config().getMessageSizeEstimator().newHandle();
        }
        return estimatorHandle;
    }

    /**
     * {@link Unsafe} implementation which sub-classes must extend and use.
     */
    protected abstract class AbstractUnsafe implements Unsafe {

        private ChannelOutboundBuffer outboundBuffer = ChannelOutboundBuffer.newInstance(AbstractChannel.this);
        private boolean inFlush0;

        @Override
        public final ChannelHandlerInvoker invoker() {
            return eventLoop.asInvoker();
        }

        @Override
        public final ChannelOutboundBuffer outboundBuffer() {
            return outboundBuffer;
        }

        @Override
        public final SocketAddress localAddress() {
            return localAddress0();
        }

        @Override
        public final SocketAddress remoteAddress() {
            return remoteAddress0();
        }

        /**
         * 将此unsafe对应的channel注册到selector上
         * @param promise
         */
        @Override
        public final void register(final ChannelPromise promise) {
            // 1.判断当前执行线程是否是channel对应的NioEventLoop线程
            if (eventLoop.inEventLoop()) {
                // 1.1 如果是同一个线程，则直接执行注册操作（避免多线程并发操作问题）
                register0(promise);
            } else {
                try {
                    // 1.2 若不是同一个线程，为了避免并发操作，则将注册操作封装成runnable放入eventLoop.taskQueue中等待执行
                    eventLoop.execute(new Runnable() {
                        @Override
                        public void run() {
                            register0(promise);
                        }
                    });
                } catch (Throwable t) {
                    logger.warn(
                            "Force-closing a channel whose registration task was not accepted by an event loop: {}",
                            AbstractChannel.this, t);
                    closeForcibly();
                    closeFuture.setClosed();
                    promise.setFailure(t);
                }
            }
        }

        private void register0(ChannelPromise promise) {
            try {
                // check if the channel is still open as it could be closed in the mean time when the register
                // call was outside of the eventLoop
                // 1.校验channel是否仍然是开启的
                if (!ensureOpen(promise)) {
                    // 1.1 若未打开则无法注册
                    return;
                }
                // 2.若是打开的则发起注册
                doRegister();
                registered = true;
                // 3. 到此处doRegister()未抛出异常则说明channel注册成功，
                // Marks this future as a success and notifies all listeners.
                promise.setSuccess();
                // 4. 调用fireChannelRegistered()，若channel已激活（打开的且已经连接）则还需要调用fireChannelActive()
                // myConfusionsv:pipeline是何时如何组装的？难道不是DefaultChannelPipeline？--是DefaultChannelPipeline，但还可以addLast(),具体见笔记（netty源码）
                pipeline.fireChannelRegistered();
                if (isActive()) {
                    pipeline.fireChannelActive();
                }
            } catch (Throwable t) {
                // Close the channel directly to avoid FD leak.
                closeForcibly();
                closeFuture.setClosed();
                if (!promise.tryFailure(t)) {
                    logger.warn(
                            "Tried to fail the registration promise, but it is complete already. " +
                                    "Swallowing the cause of the registration failure:", t);
                }
            }
        }

        /**
         * channel绑定指定的本地端口
         * 对于服务端，绑定监听端口，可以设置backlog参数用于指定最大的客户端连接数目；
         * 对于客户端，绑定本地端口，其实就是指定客户端channel的本地socket地址
         * @param localAddress
         * @param promise
         */
        @Override
        public final void bind(final SocketAddress localAddress, final ChannelPromise promise) {
            if (!ensureOpen(promise)) {
                return;
            }

            // See: https://github.com/netty/netty/issues/576
            if (!PlatformDependent.isWindows() && !PlatformDependent.isRoot() &&
                Boolean.TRUE.equals(config().getOption(ChannelOption.SO_BROADCAST)) &&
                localAddress instanceof InetSocketAddress &&
                !((InetSocketAddress) localAddress).getAddress().isAnyLocalAddress()) {
                // Warn a user about the fact that a non-root user can't receive a
                // broadcast packet on *nix if the socket is bound on non-wildcard address.
                logger.warn(
                        "A non-root user can't receive a broadcast packet if the socket " +
                        "is not bound to a wildcard address; binding to a non-wildcard " +
                        "address (" + localAddress + ") anyway as requested.");
            }

            boolean wasActive = isActive();
            try {
                doBind(localAddress);
            } catch (Throwable t) {
                // 设置异常信息到ChannelPromise中用于通知ChannelFuture
                promise.setFailure(t);
                closeIfClosed();
                return;
            }
            if (!wasActive && isActive()) {
                invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        pipeline.fireChannelActive();
                    }
                });
            }
            promise.setSuccess();
        }

        /**
         * 客户端主动关闭连接
         * @param promise
         */
        @Override
        public final void disconnect(final ChannelPromise promise) {
            boolean wasActive = isActive();
            try {
                // 只有客户端才能支持disConnect
                doDisconnect();
            } catch (Throwable t) {
                promise.setFailure(t);
                closeIfClosed();
                return;
            }
            if (wasActive && !isActive()) {
                invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        pipeline.fireChannelInactive();
                    }
                });
            }
            promise.setSuccess();
            closeIfClosed(); // doDisconnect() might have closed the channel
        }

        @Override
        public final void close(final ChannelPromise promise) {
            // 1.判断是否处于刷新状态（还有数据没有发送完成）
            if (inFlush0) {
                // 1.1 若处于刷新状态则说明需要等待数据都发送完之后再close,因此需要把close操作异步执行
                invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        close(promise);
                    }
                });
                return;
            }

            // 2. 若不处于刷新状态，则判断是否已经关闭完成
            if (closeFuture.isDone()) {
                // Closed already.
                // 2.1 已经关闭了，设置promise.result为SUCCESS
                promise.setSuccess();
                return;
            }

            // 3. 到此处，关闭操作还没完成或还没开始，因此执行关闭操作
            boolean wasActive = isActive();
            ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
            this.outboundBuffer = null; // Disallow adding any messages and flushes to outboundBuffer.

            try {
                // 3.1 关闭；closeFuture设置（closeFuture是用来判断当前关闭操作是否完成的吗？）；promise设置值
                // myConfusionsv:与2结合起来看，如果有一个线程正在执行doClose()还未来得及执行closeFuture.setClosed()，另一个线程又重新开始关闭操作，会有问题吗？
                // --不会有问题，因为底层jdknio的close操作是synchronized并判断open状态，因此不会有线程安全问题
                doClose();
                closeFuture.setClosed();
                promise.setSuccess();
            } catch (Throwable t) {
                closeFuture.setClosed();
                promise.setFailure(t);
            }

            // 4. 释放outboundBuffer中的消息
            // Fail all the queued messages
            try {
                outboundBuffer.failFlushed(CLOSED_CHANNEL_EXCEPTION);
                outboundBuffer.close(CLOSED_CHANNEL_EXCEPTION);
            } finally {
                // 5. channel关闭通知
                if (wasActive && !isActive()) {
                    invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            pipeline.fireChannelInactive();
                        }
                    });
                }

                // 6.将channel从selector上取消注册
                // myConfusion:一个channel如果可以在多个selector注册的话，如果取消注册channel那么应该把所有对应的selectionKey全部取消
                // AbstractSelectableChannel.keys AbstractNioChannel.selectionKey
                // netty的channel是与某一selector绑定的（构造方法中指定了eventLoop）,jdknio的channel是未与某一selector绑定的，对应了多个selector
                deregister();
            }
        }

        @Override
        public final void closeForcibly() {
            try {
                doClose();
            } catch (Exception e) {
                logger.warn("Failed to close a channel.", e);
            }
        }

        /**
         * 取消注册
         */
        private void deregister() {
            if (!registered) {
                return;
            }

            try {
                doDeregister();
            } catch (Throwable t) {
                logger.warn("Unexpected exception occurred while deregistering a channel.", t);
            } finally {
                if (registered) {
                    registered = false;
                }
            }
        }

        @Override
        public void beginRead() {
            if (!isActive()) {
                return;
            }

            try {
                doBeginRead();
            } catch (final Exception e) {
                invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        pipeline.fireExceptionCaught(e);
                    }
                });
                close(voidPromise());
            }
        }

        /**
         * 此处的写只是将数据加入消息发送环形数组中
         * @param msg
         * @param promise
         */
        @Override
        public void write(Object msg, ChannelPromise promise) {
            // 1. 判断channel状态
            if (!isActive()) {
                // 1. 若非激活状态，则存在以下两种情况 myConfusion:但NioServerSocketChannel.isActive()只是看本地端口是否已绑定，NioSocketChannel才是看这两种状态
                // Mark the write request as failure if the channel is inactive.
                if (isOpen()) {
                    // 1.1 channel打开时，但tcp链路尚未连接成功
                    promise.tryFailure(NOT_YET_CONNECTED_EXCEPTION);
                } else {
                    // 1.2 channel未打开
                    promise.tryFailure(CLOSED_CHANNEL_EXCEPTION);
                }
                // release message now to prevent resource-leak
                ReferenceCountUtil.release(msg);
            } else {
                // 2.若链路正常，则将msg与promise加入环形数组
                outboundBuffer.addMessage(msg, promise);
            }
        }

        /**
         * 将消息发送缓冲区（环形数组）中的数据写到channel,发送到通信对方
         */
        @Override
        public void flush() {
            ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
            if (outboundBuffer == null) {
                return;
            }

            outboundBuffer.addFlush();
            flush0();
        }

        /**
         * 真正地往channel中写数据
         */
        protected void flush0() {
            if (inFlush0) {
                // Avoid re-entrance
                return;
            }

            final ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
            if (outboundBuffer == null || outboundBuffer.isEmpty()) {
                return;
            }

            inFlush0 = true;

            // Mark all pending write requests as failure if the channel is inactive.
            if (!isActive()) {
                try {
                    if (isOpen()) {
                        outboundBuffer.failFlushed(NOT_YET_CONNECTED_EXCEPTION);
                    } else {
                        outboundBuffer.failFlushed(CLOSED_CHANNEL_EXCEPTION);
                    }
                } finally {
                    inFlush0 = false;
                }
                return;
            }

            try {
                doWrite(outboundBuffer);
            } catch (Throwable t) {
                outboundBuffer.failFlushed(t);
            } finally {
                inFlush0 = false;
            }
        }

        @Override
        public ChannelPromise voidPromise() {
            return unsafeVoidPromise;
        }

        protected final boolean ensureOpen(ChannelPromise promise) {
            if (isOpen()) {
                return true;
            }

            promise.setFailure(CLOSED_CHANNEL_EXCEPTION);
            return false;
        }

        protected final void closeIfClosed() {
            if (isOpen()) {
                return;
            }
            close(voidPromise());
        }

        private void invokeLater(Runnable task) {
            // This method is used by outbound operation implementations to trigger an inbound event later.
            // They do not trigger an inbound event immediately because an outbound operation might have been
            // triggered by another inbound event handler method.  If fired immediately, the call stack
            // will look like this for example:
            //
            //   handlerA.inboundBufferUpdated() - (1) an inbound handler method closes a connection.
            //   -> handlerA.ctx.close()
            //      -> channel.unsafe.close()
            //         -> handlerA.channelInactive() - (2) another inbound handler method called while in (1) yet
            //
            // which means the execution of two inbound handler methods of the same handler overlap undesirably.
            eventLoop().execute(task);
        }
    }

    private EventLoop validate(EventLoop eventLoop) {
        if (eventLoop == null) {
            throw new IllegalStateException("null event loop");
        }
        if (!isCompatible(eventLoop)) {
            throw new IllegalStateException("incompatible event loop type: " + eventLoop.getClass().getName());
        }
        return eventLoop;
    }

    /**
     * Return {@code true} if the given {@link EventLoop} is compatible with this instance.
     */
    protected abstract boolean isCompatible(EventLoop loop);

    /**
     * Returns the {@link SocketAddress} which is bound locally.
     */
    protected abstract SocketAddress localAddress0();

    /**
     * Return the {@link SocketAddress} which the {@link Channel} is connected to.
     */
    protected abstract SocketAddress remoteAddress0();

    /**
     * Is called after the {@link Channel} is registered with its {@link EventLoop} as part of the register
     * process.
     *
     * Sub-classes may override this method
     */
    protected void doRegister() throws Exception {
        // NOOP
    }

    /**
     * Bind the {@link Channel} to the {@link SocketAddress}
     */
    protected abstract void doBind(SocketAddress localAddress) throws Exception;

    /**
     * Disconnect this {@link Channel} from its remote peer
     */
    protected abstract void doDisconnect() throws Exception;

    /**
     * Close the {@link Channel}
     */
    protected abstract void doClose() throws Exception;

    /**
     * Deregister the {@link Channel} from its {@link EventLoop}.
     *
     * Sub-classes may override this method
     */
    protected void doDeregister() throws Exception {
        // NOOP
    }

    /**
     * Schedule a read operation.
     */
    protected abstract void doBeginRead() throws Exception;

    /**
     * Flush the content of the given buffer to the remote peer.
     */
    protected abstract void doWrite(ChannelOutboundBuffer in) throws Exception;

    protected static void checkEOF(FileRegion region) throws IOException {
        if (region.transfered() < region.count()) {
            throw new EOFException("Expected to be able to write "
                    + region.count() + " bytes, but only wrote "
                    + region.transfered());
        }
    }

    static final class CloseFuture extends DefaultChannelPromise {

        CloseFuture(AbstractChannel ch) {
            super(ch);
        }

        @Override
        public ChannelPromise setSuccess() {
            throw new IllegalStateException();
        }

        @Override
        public ChannelPromise setFailure(Throwable cause) {
            throw new IllegalStateException();
        }

        @Override
        public boolean trySuccess() {
            throw new IllegalStateException();
        }

        @Override
        public boolean tryFailure(Throwable cause) {
            throw new IllegalStateException();
        }

        boolean setClosed() {
            return super.trySuccess();
        }
    }
}
