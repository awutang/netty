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
package io.netty.handler.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.internal.RecyclableArrayList;
import io.netty.util.internal.StringUtil;

import java.util.List;

/**
 * A {@link ChannelHandler} which decodes bytes in a stream-like fashion from one {@link ByteBuf} to an
 * other Message type.
 *
 * For example here is an implementation which reads all readable bytes from
 * the input {@link ByteBuf} and create a new {@link ByteBuf}.
 *
 * <pre>
 *     public class SquareDecoder extends {@link ByteToMessageDecoder} {
 *         {@code @Override}
 *         public void decode({@link ChannelHandlerContext} ctx, {@link ByteBuf} in, List&lt;Object&gt; out)
 *                 throws {@link Exception} {
 *             out.add(in.readBytes(in.readableBytes()));
 *         }
 *     }
 * </pre>
 *
 * Be aware that sub-classes of {@link ByteToMessageDecoder} <strong>MUST NOT</strong>
 * annotated with {@link @Sharable}.
 *
 * 将ByteBuf解析成应用POJO对象，但是未考虑到粘包组包等场景，因此读半包并未处理
 */
public abstract class ByteToMessageDecoder extends ChannelHandlerAdapter {

    ByteBuf cumulation;
    private boolean singleDecode;
    private boolean decodeWasNull;
    private boolean first;

    protected ByteToMessageDecoder() {
        if (getClass().isAnnotationPresent(Sharable.class)) {
            throw new IllegalStateException("@Sharable annotation is not allowed");
        }
    }

    /**
     * If set then only one message is decoded on each {@link #channelRead(ChannelHandlerContext, Object)}
     * call. This may be useful if you need to do some protocol upgrade and want to make sure nothing is mixed up.
     *
     * Default is {@code false} as this has performance impacts.
     */
    public void setSingleDecode(boolean singleDecode) {
        this.singleDecode = singleDecode;
    }

    /**
     * If {@code true} then only one message is decoded on each
     * {@link #channelRead(ChannelHandlerContext, Object)} call.
     *
     * Default is {@code false} as this has performance impacts.
     */
    public boolean isSingleDecode() {
        return singleDecode;
    }

    /**
     * Returns the actual number of readable bytes in the internal cumulative
     * buffer of this decoder. You usually do not need to rely on this value
     * to write a decoder. Use it only when you must use it at your own risk.
     * This method is a shortcut to {@link #internalBuffer() internalBuffer().readableBytes()}.
     */
    protected int actualReadableBytes() {
        return internalBuffer().readableBytes();
    }

    /**
     * Returns the internal cumulative buffer of this decoder. You usually
     * do not need to access the internal buffer directly to write a decoder.
     * Use it only when you must use it at your own risk.
     */
    protected ByteBuf internalBuffer() {
        if (cumulation != null) {
            return cumulation;
        } else {
            return Unpooled.EMPTY_BUFFER;
        }
    }

    @Override
    public final void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        ByteBuf buf = internalBuffer();
        int readable = buf.readableBytes();
        if (buf.isReadable()) {
            ByteBuf bytes = buf.readBytes(readable);
            buf.release();
            ctx.fireChannelRead(bytes);
        }
        cumulation = null;
        ctx.fireChannelReadComplete();
        handlerRemoved0(ctx);
    }

    /**
     * Gets called after the {@link ByteToMessageDecoder} was removed from the actual context and it doesn't handle
     * events anymore.
     */
    protected void handlerRemoved0(ChannelHandlerContext ctx) throws Exception { }

    /**
     * 读取bytebuf(是已经从channel中读取写入的)数据然后解码
     * @param ctx
     * @param msg
     * @throws Exception
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        // 1. 判断是否为ByteBuf对象，否，则透传到下一个channelHandler
        if (msg instanceof ByteBuf) {
            RecyclableArrayList out = RecyclableArrayList.newInstance();
            try {
                // 2. 处理data(包括半包逻辑)
                ByteBuf data = (ByteBuf) msg;
                first = cumulation == null;
                if (first) {
                    // 说明第一次解码或已经处理完了最近的一次半包数据
                    cumulation = data;
                } else {
                    if (cumulation.writerIndex() > cumulation.maxCapacity() - data.readableBytes()) {
                        // cumulation中可写的字节数小于data中需要写进来的字节数即cumulation不够用了，因此需要扩容
                        expandCumulation(ctx, data.readableBytes());
                    }
                    // 写入本次read的数据
                    cumulation.writeBytes(data);
                    // 释放ByteBuf引用的内存,myConfusionsv:为啥不把data=null从而释放buf对象?--因为data是局部变量，方法执行结束后引用值自然为null
                    data.release();
                }
                // 3.解码
                callDecode(ctx, cumulation, out);
            } catch (DecoderException e) {
                throw e;
            } catch (Throwable t) {
                throw new DecoderException(t);
            } finally {
                // 4. 最后的处理
                if (cumulation != null && !cumulation.isReadable()) {
                    // 4.1 若cumulation中的数据已全部消费完了，则释放
                    // 释放buf 指向内存
                    cumulation.release();
                    // 释放buf对象
                    cumulation = null;
                }
                // 4.2 讲解码得到的对象传播到下一个channelHandler进行处理
                int size = out.size();
                decodeWasNull = size == 0;

                // 解码之后out中的每个元素就是一条完整消息，对每一条完整消息都需要执行channelRead(),因此循环调用
                for (int i = 0; i < size; i ++) {
                    ctx.fireChannelRead(out.get(i));
                }
                // 4.3 释放out
                out.recycle();
            }
        } else {
            // 1.1 如果不符合类型校验则头传给下一个chanelHandler
            ctx.fireChannelRead(msg);
        }
    }

    private void expandCumulation(ChannelHandlerContext ctx, int readable) {
        ByteBuf oldCumulation = cumulation;
        // 重新创建新容量的ByteBuf
        cumulation = ctx.alloc().buffer(oldCumulation.readableBytes() + readable);
        // 将已有数据copy过来
        cumulation.writeBytes(oldCumulation);
        // 释放旧ByteBuf
        oldCumulation.release();
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        if (cumulation != null && !first) {
            // discard some bytes if possible to make more room in the
            // buffer
            cumulation.discardSomeReadBytes();
        }
        if (decodeWasNull) {
            decodeWasNull = false;
            if (!ctx.channel().config().isAutoRead()) {
                ctx.read();
            }
        }
        ctx.fireChannelReadComplete();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        RecyclableArrayList out = RecyclableArrayList.newInstance();
        try {
            if (cumulation != null) {
                callDecode(ctx, cumulation, out);
                decodeLast(ctx, cumulation, out);
            } else {
                decodeLast(ctx, Unpooled.EMPTY_BUFFER, out);
            }
        } catch (DecoderException e) {
            throw e;
        } catch (Exception e) {
            throw new DecoderException(e);
        } finally {
            if (cumulation != null) {
                cumulation.release();
                cumulation = null;
            }
            int size = out.size();
            for (int i = 0; i < size; i ++) {
                ctx.fireChannelRead(out.get(i));
            }
            ctx.fireChannelInactive();
            out.recycle();
        }
    }

    /**
     * Called once data should be decoded from the given {@link ByteBuf}. This method will call
     * {@link #decode(ChannelHandlerContext, ByteBuf, List)} as long as decoding should take place.
     *
     * @param ctx           the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param in            the {@link ByteBuf} from which to read data
     * @param out           the {@link List} to which decoded messages should be added
     */
    protected void callDecode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        try {
            // 1. 循环解码，有可读的数据才进入循环
            while (in.isReadable()) {
                int outSize = out.size();
                int oldInputLength = in.readableBytes();
                // 2.有不同的解码实现
                decode(ctx, in, out);

                // Check if this handler was removed before continuing the loop.
                // If it was removed, it is not safe to continue to operate on the buffer.
                //
                // See https://github.com/netty/netty/issues/1664
                // 3. 若channelHandler已从pipeline中删除，则结束循环
                if (ctx.isRemoved()) {
                    break;
                }

                // 3. 解码之后out中的数据不变，则说明本次解码失败
                if (outSize == out.size()) {
                    if (oldInputLength == in.readableBytes()) {
                        // 3.1 bytebuf中的数据并未读出来，原因是bytebuf中的数据是半包消息，需要继续读取channel中的消息，
                        // 因此本次不参与解码了，跳出循环(例如FixedLengthFrameDecoder)
                        break;
                    } else {
                        // 3.2 bytebuf中的数据读取出来了，说明自定义解码器已经成功消费了数据，继续解码
                        // myConfusionsv:可是没有写入out的数据咋办呢--out中没有写入数据要么是自定义解码器并未写入out,要么发生了异常，
                        //  但异常肯定会跑出来的，所以此处肯定是自定义解码器并未写入out的情况
                        continue;
                    }
                }

                // 4. 如果自定义解码器并没有消费数据，但是却解码出了数据（out中写入数据了），这种行为是不正常的
                if (oldInputLength == in.readableBytes()) {
                    throw new DecoderException(
                            StringUtil.simpleClassName(getClass()) +
                            ".decode() did not read anything but decoded a message.");
                }

                // 5. 如果是单条消息解码器，则解码一次之后就可以退出了
                if (isSingleDecode()) {
                    break;
                }
            }
        } catch (DecoderException e) {
            throw e;
        } catch (Throwable cause) {
            throw new DecoderException(cause);
        }
    }

    /**
     * Decode the from one {@link ByteBuf} to an other. This method will be called till either the input
     * {@link ByteBuf} has nothing to read anymore, till nothing was read from the input {@link ByteBuf} or till
     * this method returns {@code null}.
     *
     * @param ctx           the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param in            the {@link ByteBuf} from which to read data
     * @param out           the {@link List} to which decoded messages should be added

     * @throws Exception    is thrown if an error accour
     */
    protected abstract void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception;

    /**
     * Is called one last time when the {@link ChannelHandlerContext} goes in-active. Which means the
     * {@link #channelInactive(ChannelHandlerContext)} was triggered.
     *
     * By default this will just call {@link #decode(ChannelHandlerContext, ByteBuf, List)} but sub-classes may
     * override this for some special cleanup operation.
     */
    protected void decodeLast(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        decode(ctx, in, out);
    }
}
