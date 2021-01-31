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
package io.netty.channel.socket.nio;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.FileRegion;
import io.netty.channel.nio.AbstractNioByteChannel;
import io.netty.channel.socket.DefaultSocketChannelConfig;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannelConfig;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

/**
 * {@link io.netty.channel.socket.SocketChannel} which uses NIO selector based implementation.
 */
public class NioSocketChannel extends AbstractNioByteChannel implements io.netty.channel.socket.SocketChannel {

    private static final ChannelMetadata METADATA = new ChannelMetadata(false);

    private static SocketChannel newSocket() {
        try {
            return SocketChannel.open();
        } catch (IOException e) {
            throw new ChannelException("Failed to open a socket.", e);
        }
    }

    private final SocketChannelConfig config;

    /**
     * Create a new instance
     */
    public NioSocketChannel(EventLoop eventLoop) {
        this(eventLoop, newSocket());
    }

    /**
     * Create a new instance using the given {@link SocketChannel}.
     */
    public NioSocketChannel(EventLoop eventLoop, SocketChannel socket) {
        this(null, eventLoop, socket);
    }

    /**
     * Create a new instance
     *
     * @param parent    the {@link Channel} which created this instance or {@code null} if it was created by the user
     * @param socket    the {@link SocketChannel} which will be used
     */
    public NioSocketChannel(Channel parent, EventLoop eventLoop, SocketChannel socket) {
        super(parent, eventLoop, socket);
        config = new DefaultSocketChannelConfig(this, socket.socket());
    }

    @Override
    public ServerSocketChannel parent() {
        return (ServerSocketChannel) super.parent();
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    @Override
    public SocketChannelConfig config() {
        return config;
    }

    @Override
    protected SocketChannel javaChannel() {
        return (SocketChannel) super.javaChannel();
    }

    @Override
    public boolean isActive() {
        SocketChannel ch = javaChannel();
        return ch.isOpen() && ch.isConnected();
    }

    @Override
    public boolean isInputShutdown() {
        return super.isInputShutdown();
    }

    @Override
    public InetSocketAddress localAddress() {
        return (InetSocketAddress) super.localAddress();
    }

    @Override
    public InetSocketAddress remoteAddress() {
        return (InetSocketAddress) super.remoteAddress();
    }

    @Override
    public boolean isOutputShutdown() {
        return javaChannel().socket().isOutputShutdown() || !isActive();
    }

    @Override
    public ChannelFuture shutdownOutput() {
        return shutdownOutput(newPromise());
    }

    @Override
    public ChannelFuture shutdownOutput(final ChannelPromise promise) {
        EventLoop loop = eventLoop();
        if (loop.inEventLoop()) {
            try {
                javaChannel().socket().shutdownOutput();
                promise.setSuccess();
            } catch (Throwable t) {
                promise.setFailure(t);
            }
        } else {
            loop.execute(new Runnable() {
                @Override
                public void run() {
                    shutdownOutput(promise);
                }
            });
        }
        return promise;
    }

    @Override
    protected SocketAddress localAddress0() {
        return javaChannel().socket().getLocalSocketAddress();
    }

    @Override
    protected SocketAddress remoteAddress0() {
        return javaChannel().socket().getRemoteSocketAddress();
    }

    @Override
    protected void doBind(SocketAddress localAddress) throws Exception {
        javaChannel().socket().bind(localAddress);
    }

    @Override
    protected boolean doConnect(SocketAddress remoteAddress, SocketAddress localAddress) throws Exception {
        // 1. 如果本地socket地址不为空则 Binds the socket to a local address.
        if (localAddress != null) {
            javaChannel().socket().bind(localAddress);
        }

        // 2. 走到这里表示bind(localAddress)成功了否则会抛异常，绑定成功后法埃tcp连接（连接服务端）
        // 3. 连接结果：
        //  3.1:连接成功，返回true
        //  3.2:暂时没连接上 服务端未返回ack,连接结果不确定，返回false
        //  3.3:连接失败，直接抛出IO异常
        boolean success = false;
        try {
            boolean connected = javaChannel().connect(remoteAddress);
            if (!connected) {
                // 4. 若是3.2则设置selectionKey.interestOps为OP_CONNECT，表示需要再次发起连接
                selectionKey().interestOps(SelectionKey.OP_CONNECT);
            }
            success = true;
            return connected;
        } finally {
            if (!success) {
                // 5.到此处表示情况为3.3，说明客户端tcp请求直接被拒绝或rest，因此直接关闭客户端
                doClose();
            }
        }
    }

    @Override
    protected void doFinishConnect() throws Exception {
        if (!javaChannel().finishConnect()) {
            throw new Error();
        }
    }

    @Override
    protected void doDisconnect() throws Exception {
        doClose();
    }

    @Override
    protected void doClose() throws Exception {
        javaChannel().close();
    }

    /**
     * 从channel中读数据并写到ByteBuf中
     * @param byteBuf
     * @return
     * @throws Exception
     */
    @Override
    protected int doReadBytes(ByteBuf byteBuf) throws Exception {
        return byteBuf.writeBytes(javaChannel(), byteBuf.writableBytes());
    }

    @Override
    protected int doWriteBytes(ByteBuf buf) throws Exception {
        final int expectedWrittenBytes = buf.readableBytes();
        // 将buf中的可读字节写到channel中,
        // myConfusion:写的动作就涉及到了之前所学习的IO模型，不是说netty用的是epoll吗？epoll第一阶段其实是在多个fd上阻塞的，那么netty中是如何做到设置成非阻塞模式的？
        final int writtenBytes = buf.readBytes(javaChannel(), expectedWrittenBytes);
        return writtenBytes;
    }

    @Override
    protected long doWriteFileRegion(FileRegion region) throws Exception {
        final long position = region.transfered();
        final long writtenBytes = region.transferTo(javaChannel(), position);
        return writtenBytes;
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        for (;;) {
            // 1.获取待发送的msg个数（flushed~unflushed），若不大于1，则调用父类方法 为啥？
            // --因为下面的逻辑是针对多个ByteBuf的，如果只有一个ByteBuf就没必要继续执行了（当然继续执行也没问题），但如果msg是非ByteBuf的，那就只能用父类方法了，所以综合两种情况都去执行父类方法去
            // Do non-gathering write for a single buffer case.
            final int msgCount = in.size();
            if (msgCount <= 1) {
                super.doWrite(in);
                return;
            }

            // Ensure the pending writes are made of ByteBufs only.
            ByteBuffer[] nioBuffers = in.nioBuffers();
            if (nioBuffers == null) {
                // 如果待发送环形数组中的数据不是所有的都是ByteBuf,则用父类写
                super.doWrite(in);
                return;
            }

            // 2.设置各变量
            // 待发送缓冲器中ByteBuffer总个数
            int nioBufferCnt = in.nioBufferCount();
            // 可发送的总字节数
            long expectedWrittenBytes = in.nioBufferSize();

            final SocketChannel ch = javaChannel();
            // 总写出字节数
            long writtenBytes = 0;
            boolean done = false;
            boolean setOpWrite = false;

            // 3. 将nioBuffers写到channel，对一次selector轮询的写操作次数进行限制
            for (int i = config().getWriteSpinCount() - 1; i >= 0; i --) {
                // 因为tcp发送缓冲区有限，有可能只写了一部分
                final long localWrittenBytes = ch.write(nioBuffers, 0, nioBufferCnt);
                if (localWrittenBytes == 0) {
                    // 到此处说明expectedWrittenBytes!=0且tcp缓冲区无法再写入了，因此数据只写了一部分即发生了写半包
                    setOpWrite = true;
                    break;
                }
                expectedWrittenBytes -= localWrittenBytes;
                writtenBytes += localWrittenBytes;
                if (expectedWrittenBytes == 0) {
                    // 到此处说明nioBuffer所有数据已经写完
                    done = true;
                    break;
                }
            }

            // 4.判断是否全部写完，执行相应的操作
            if (done) {
                // Release all buffers
                for (int i = msgCount; i > 0; i --) {
                    in.remove();
                }

                // Finish the write loop if no new messages were flushed by in.remove().
                if (in.isEmpty()) {
                    clearOpWrite();
                    break;
                }
            } else {
                // Did not write all buffers completely.
                // Release the fully written buffers and update the indexes of the partially written buffer.

                for (int i = msgCount; i > 0; i --) {
                    final ByteBuf buf = (ByteBuf) in.current();
                    final int readerIndex = buf.readerIndex();
                    final int readableBytes = buf.writerIndex() - readerIndex;
                    if (readableBytes < writtenBytes) {
                        // 当前的ByteBuf已经被完全发送了，
                        // 更新ChannelOutboundBuffer的发送进度
                        in.progress(readableBytes);
                        // 删除该ByteBuf
                        in.remove();
                        writtenBytes -= readableBytes;
                    } else if (readableBytes > writtenBytes) {
                        // 当前的ByteBuf没有被完全发送完成
                        // 更新索引、进度，
                        buf.readerIndex(readerIndex + (int) writtenBytes);
                        in.progress(writtenBytes);
                        break;
                    } else { // readableBytes == writtenBytes
                        // 没有半包消息
                        in.progress(readableBytes);
                        in.remove();
                        break;
                    }
                }

                // 5.处理半包
                incompleteWrite(setOpWrite);
                break;
            }
        }
    }
}
