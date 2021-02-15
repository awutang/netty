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

import io.netty.channel.*;
import io.netty.channel.nio.AbstractNioMessageServerChannel;
import io.netty.channel.socket.DefaultServerSocketChannelConfig;
import io.netty.channel.socket.ServerSocketChannelConfig;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.List;

/**
 * A {@link io.netty.channel.socket.ServerSocketChannel} implementation which uses
 * NIO selector based implementation to accept new connections.
 */
public class NioServerSocketChannel extends AbstractNioMessageServerChannel
                                 implements io.netty.channel.socket.ServerSocketChannel {


    private static final ChannelMetadata METADATA = new ChannelMetadata(false);

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(NioServerSocketChannel.class);

    private static ServerSocketChannel newSocket() {
        try {
            return ServerSocketChannel.open();
        } catch (IOException e) {
            throw new ChannelException(
                    "Failed to open a server socket.", e);
        }
    }

    // 用来配置ServerSocketChannel的tcp参数
    private final ServerSocketChannelConfig config;

    /**
     * Create a new instance  既然已经有了有参构造方法，那么肯定是没有无参构造方法的
     */
    public NioServerSocketChannel(EventLoop eventLoop, EventLoopGroup childGroup) {
        // ch:newSocket()
        super(null, eventLoop, childGroup, newSocket(), SelectionKey.OP_ACCEPT);
        config = new DefaultServerSocketChannelConfig(this, javaChannel().socket());
    }

    @Override
    public InetSocketAddress localAddress() {
        return (InetSocketAddress) super.localAddress();
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    @Override
    public ServerSocketChannelConfig config() {
        return config;
    }

    @Override
    public boolean isActive() {
        // 判断服务端的本地监听端口是否处于绑定状态，这个端口其实就是服务端开启的等待客户端连接的端口
        // 这个本地端口的绑定动作是由服务端自己来做的，端口绑定之后开启监听
        // myConfusionsv:绑定其实是将服务端channel绑定到了服务端某端口？
        //  --实质是将ServerSocket绑定到某端口，类似开通了某一端口专门用来做网络连接的
        return javaChannel().socket().isBound();
    }

    /**
     * 服务端没有远程端口
     * @return
     */
    @Override
    public InetSocketAddress remoteAddress() {
        return null;
    }

    @Override
    protected ServerSocketChannel javaChannel() {
        return (ServerSocketChannel) super.javaChannel();
    }

    @Override
    protected SocketAddress localAddress0() {
        return javaChannel().socket().getLocalSocketAddress();
    }

    /**
     * Bind the {@link Channel} to the {@link SocketAddress}
     *
     * backlog:requested maximum length of the queue of incoming connections.
     * @param localAddress
     * @throws Exception
     */
    @Override
    protected void doBind(SocketAddress localAddress) throws Exception {
        // Binds the {@code ServerSocket} to a specific address
        javaChannel().socket().bind(localAddress, config.getBacklog());
    }

    @Override
    protected void doClose() throws Exception {
        javaChannel().close();
    }

    /**
     * 接受连接 服务端的读是由接收生成的NioSocketChannel去做的（所属服务端启动时的worker线程），因此NioServerSocketChannel类没有read方法
     * @param buf
     * @return
     * @throws Exception
     */
    @Override
    protected int doReadMessages(List<Object> buf) throws Exception {
        // 1. 接入客户端连接
        /**ServerSocketChannel有阻塞和非阻塞两种模式：

         a、阻塞模式：ServerSocketChannel.accept() 方法监听新进来的连接，当 accept()方法返回的时候,它返回一个包含新进来的连接的 SocketChannel。阻塞模式下, accept()方法会一直阻塞到有新连接到达。

         b、非阻塞模式：，accept() 方法会立刻返回，如果还没有新进来的连接,返回的将是null。 因此，需要检查返回的SocketChannel是否是null.

         在NioServerSocketChannel的构造函数分析中，我们知道，其通过ch.configureBlocking(false);语句设置当前的ServerSocketChannel为非阻塞的。*/
        SocketChannel ch = javaChannel().accept();

        try {
            if (ch != null) {
                // 2. 获取reader线程eventLoop并创建NioSocketChannel对象，并将其加入到List<Object> buf中
                // myConfusionsv:buf是NioMessageUnsafe.readBuf字段NioMessageUnsafe.readBuf被使用的地方pipeline.fireChannelRead(readBuf.get(i))
                // readBuf.get(i)是NioSocketChannel对象？？？读的是NioSocketChannel对象？？--后续会执行到ServerBootstrapAcceptor.channelRead()进行ch注册

                // childEventLoopGroup()是服务端启动时创建的workerGroup
                buf.add(new NioSocketChannel(this, childEventLoopGroup().next(), ch));
                // 返回1表示服务端读取消息成功
                // myConfusionsv:但其实并没有读取消息，只是连接成功了，那么真正滴读取实现在哪呢？--真正的服务端读是由连接后得到的NioSocketChannel做的
                return 1;
            }
        } catch (Throwable t) {
            logger.warn("Failed to create a new channel from an accepted socket.", t);

            try {
                ch.close();
            } catch (Throwable t2) {
                logger.warn("Failed to close a socket.", t2);
            }
        }

        return 0;
    }

    // Unnecessary stuff 这之后的五个方法都是客户端channel的方法，因此服务端channel不支持
    @Override
    protected boolean doConnect(
            SocketAddress remoteAddress, SocketAddress localAddress) throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    protected void doFinishConnect() throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    protected SocketAddress remoteAddress0() {
        return null;
    }

    @Override
    protected void doDisconnect() throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    protected boolean doWriteMessage(Object msg, ChannelOutboundBuffer in) throws Exception {
        throw new UnsupportedOperationException();
    }
}
