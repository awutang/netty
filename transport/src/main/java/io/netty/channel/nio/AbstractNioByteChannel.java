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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoop;
import io.netty.channel.FileRegion;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.util.internal.StringUtil;

import java.io.IOException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;

/**
 * {@link AbstractNioChannel} base class for {@link Channel}s that operate on bytes.
 */
public abstract class AbstractNioByteChannel extends AbstractNioChannel {

    // 负责继续写半包消息 一次发送没有完成时称为写半包
    private Runnable flushTask;

    /**
     * Create a new instance
     *
     * @param parent            the parent {@link Channel} by which this instance was created. May be {@code null}
     * @param ch                the underlying {@link SelectableChannel} on which it operates
     */
    protected AbstractNioByteChannel(Channel parent, EventLoop eventLoop, SelectableChannel ch) {
        super(parent, eventLoop, ch, SelectionKey.OP_READ);
    }

    @Override
    protected AbstractNioUnsafe newUnsafe() {
        return new NioByteUnsafe();
    }

    private final class NioByteUnsafe extends AbstractNioUnsafe {
        private RecvByteBufAllocator.Handle allocHandle;

        private void removeReadOp() {
            SelectionKey key = selectionKey();
            int interestOps = key.interestOps();
            if ((interestOps & readInterestOp) != 0) {
                // only remove readInterestOp if needed
                key.interestOps(interestOps & ~readInterestOp);
            }
        }

        private void closeOnRead(ChannelPipeline pipeline) {
            SelectionKey key = selectionKey();
            setInputShutdown();
            if (isOpen()) {
                if (Boolean.TRUE.equals(config().getOption(ChannelOption.ALLOW_HALF_CLOSURE))) {
                    key.interestOps(key.interestOps() & ~readInterestOp);
                    pipeline.fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);
                } else {
                    close(voidPromise());
                }
            }
        }

        private void handleReadException(ChannelPipeline pipeline, ByteBuf byteBuf, Throwable cause, boolean close) {
            if (byteBuf != null) {
                if (byteBuf.isReadable()) {
                    pipeline.fireChannelRead(byteBuf);
                } else {
                    byteBuf.release();
                }
            }
            pipeline.fireChannelReadComplete();
            pipeline.fireExceptionCaught(cause);
            if (close || cause instanceof IOException) {
                closeOnRead(pipeline);
            }
        }

        @Override
        public void read() {
            final ChannelConfig config = config();
            final ChannelPipeline pipeline = pipeline();
            final ByteBufAllocator allocator = config.getAllocator();
            final int maxMessagesPerRead = config.getMaxMessagesPerRead();
            RecvByteBufAllocator.Handle allocHandle = this.allocHandle;
            if (allocHandle == null) {
                this.allocHandle = allocHandle = config.getRecvByteBufAllocator().newHandle();
            }
            if (!config.isAutoRead()) {
                removeReadOp();
            }

            ByteBuf byteBuf = null;
            int messages = 0;
            boolean close = false;
            try {
                int byteBufCapacity = allocHandle.guess();
                int totalReadAmount = 0;
                do {
                    byteBuf = allocator.ioBuffer(byteBufCapacity);
                    int writable = byteBuf.writableBytes();
                    int localReadAmount = doReadBytes(byteBuf);
                    if (localReadAmount <= 0) {
                        // not was read release the buffer
                        byteBuf.release();
                        close = localReadAmount < 0;
                        break;
                    }

                    pipeline.fireChannelRead(byteBuf);
                    byteBuf = null;

                    if (totalReadAmount >= Integer.MAX_VALUE - localReadAmount) {
                        // Avoid overflow.
                        totalReadAmount = Integer.MAX_VALUE;
                        break;
                    }

                    totalReadAmount += localReadAmount;
                    if (localReadAmount < writable) {
                        // Read less than what the buffer can hold,
                        // which might mean we drained the recv buffer completely.
                        break;
                    }
                } while (++ messages < maxMessagesPerRead);

                pipeline.fireChannelReadComplete();
                allocHandle.record(totalReadAmount);

                if (close) {
                    closeOnRead(pipeline);
                    close = false;
                }
            } catch (Throwable t) {
                handleReadException(pipeline, byteBuf, t, close);
            }
        }
    }

    /**
     * 从channelOutboundBuffer写出数据到channel
     * @param in
     * @throws Exception
     */
    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        int writeSpinCount = -1;

        for (;;) {
            // 获取环形数组中当前msg
            Object msg = in.current(true);
            // channelOutboundBuffer消息发送数组中的待发送消息已经发送完成
            if (msg == null) {
                // Wrote all messages. 清除半包标志，其实就是在清除selectionKey的写操作位
                // myConfusionsv:这些selectionKey中的操作位表示的是即将要发生的事情吗？例如写操作位当数据写完后就可以清除了
                // --是的，其实根据注释(If the selector detects that the corresponding channel is ready for writing)表示准备好了写出
                //     当SelectKey设置为OP_WRITE后，Selector会不断轮询对应的Channel处理没有发送完成的半包消息，直到清除OP_WRITE标志为止
                clearOpWrite();
                break;
            }

            // 判断消息类型是否为ByteBuf
            if (msg instanceof ByteBuf) {
                ByteBuf buf = (ByteBuf) msg;
                int readableBytes = buf.readableBytes();
                // 可读字节数为0,则说明不需要write--不可读
                // 不可读与写有啥关联--不可读是ByteBuffer中没有可读数据，这里的写指的是Channel的写(往Channel中写入)，
                //  因此当byteBuf中没数据时说明应用需要发送的数据已经全部都写到channel了
                if (readableBytes == 0) {
                    // 从环形数组中删除当前msg
                    in.remove();
                    continue;
                }

                // 写半包标志
                boolean setOpWrite = false;
                // 消息是否全部发送完成
                boolean done = false;
                // 一个msg写到channel的总字节数
                long flushedAmount = 0;
                if (writeSpinCount == -1) {
                    // 有数据写且能写入channel，最多写16次，这个16次指的是当前msg发送一次没有完成时（写半包）继续写的次数
                    // 设置次数限制，目的是为了当前IO线程不会死循环在写半包处
                    writeSpinCount = config().getWriteSpinCount();
                }
                for (int i = writeSpinCount - 1; i >= 0; i --) {
                    // 这里是将buf中的数据写入Channel对象了，返回的是实际写入的字节数，若返回0则说明tcp缓冲区已满，无法再写入了
                    int localFlushedAmount = doWriteBytes(buf);
                    if (localFlushedAmount == 0) {
                        // 已经无法再写入了，设置写半包标志(incompleteWrite(setOpWrite)中会根据这个标志将interestOp设置成isWritable可写
                        // selector会轮询此channel继续进行写出)，退出循环
                        setOpWrite = true;
                        break;
                    }

                    flushedAmount += localFlushedAmount;
                    if (!buf.isReadable()) {
                        // buf中的数据已经全部读出来并写出到channel了，即msg已发送完成
                        done = true;
                        break;
                    }
                }

                // 更新发送进度
                in.progress(flushedAmount);

                // 若msg写完了则从循环数组中删除，否则会继续进行处理（interestOp位或者异步处理）
                if (done) {
                    in.remove();
                } else {
                    // 此msg写了16次仍没写完，则创建一个task继续写
                    incompleteWrite(setOpWrite);
                    break;
                }
            } else if (msg instanceof FileRegion) {
                FileRegion region = (FileRegion) msg;
                boolean setOpWrite = false;
                boolean done = false;
                long flushedAmount = 0;
                if (writeSpinCount == -1) {
                    writeSpinCount = config().getWriteSpinCount();
                }
                for (int i = writeSpinCount - 1; i >= 0; i --) {
                    long localFlushedAmount = doWriteFileRegion(region);
                    if (localFlushedAmount == 0) {
                        setOpWrite = true;
                        break;
                    }

                    flushedAmount += localFlushedAmount;
                    if (region.transfered() >= region.count()) {
                        done = true;
                        break;
                    }
                }

                in.progress(flushedAmount);

                if (done) {
                    in.remove();
                } else {
                    incompleteWrite(setOpWrite);
                    break;
                }
            } else {
                throw new UnsupportedOperationException("unsupported message type: " + StringUtil.simpleClassName(msg));
            }
        }
    }

    /**
     * msg未写完继续进行处理（interestOp位或者异步处理）
     * 设置写半包标志(incompleteWrite(setOpWrite)中会根据这个标志将interestOp设置成isWritable可写
     *                         // selector会轮询此channel继续进行写出
     * @param setOpWrite
     */
    protected final void incompleteWrite(boolean setOpWrite) {
        // Did not write completely.
        if (setOpWrite) {
            // 1. 将interestOp设置成isWritable可写,selector会轮询此channel继续进行写出
            setOpWrite();
        } else {
            // 2. 如果interestOp没有设置OP_WRITE，因为不会有selector来执行，所以需要启动独立的runnable,并将其加入到eventLoop中执行
            // Schedule flush again later so other tasks can be picked up in the meantime
            Runnable flushTask = this.flushTask;
            if (flushTask == null) {
                flushTask = this.flushTask = new Runnable() {
                    @Override
                    public void run() {
                        flush();
                    }
                };
            }
            eventLoop().execute(flushTask);
        }
    }

    /**
     * Write a {@link FileRegion}
     *
     * @param region        the {@link FileRegion} from which the bytes should be written
     * @return amount       the amount of written bytes
     */
    protected abstract long doWriteFileRegion(FileRegion region) throws Exception;

    /**
     * Read bytes into the given {@link ByteBuf} and return the amount.
     */
    protected abstract int doReadBytes(ByteBuf buf) throws Exception;

    /**
     * Write bytes form the given {@link ByteBuf} to the underlying {@link java.nio.channels.Channel}.
     * @param buf           the {@link ByteBuf} from which the bytes should be written
     * @return amount       the amount of written bytes
     */
    protected abstract int doWriteBytes(ByteBuf buf) throws Exception;

    protected final void setOpWrite() {
        final SelectionKey key = selectionKey();
        final int interestOps = key.interestOps();
        if ((interestOps & SelectionKey.OP_WRITE) == 0) {
            key.interestOps(interestOps | SelectionKey.OP_WRITE);
        }
    }

    protected final void clearOpWrite() {
        final SelectionKey key = selectionKey();
        final int interestOps = key.interestOps();
        // interestOps & SelectionKey.OP_WRITE) != 0：interestOps的写操作位是有值的，因此需要清空
        if ((interestOps & SelectionKey.OP_WRITE) != 0) {
            // interestOps & ~SelectionKey.OP_WRITE:interestOps的写操作位清空了，其他位不变
            key.interestOps(interestOps & ~SelectionKey.OP_WRITE);
        }
    }
}
