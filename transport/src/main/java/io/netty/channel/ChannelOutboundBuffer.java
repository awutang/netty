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
/*
 * Written by Josh Bloch of Google Inc. and released to the public domain,
 * as explained at http://creativecommons.org/publicdomain/zero/1.0/.
 */
package io.netty.channel;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.buffer.UnpooledDirectByteBuf;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.Recycler;
import io.netty.util.Recycler.Handle;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;

/**
 * (Transport implementors only) an internal data structure used by {@link AbstractChannel} to store its pending
 * outbound write requests. 存储待发送消息数据
 *
 * 与我们平常所说的应用中写出消息的关系：应用中一般是自己创建ByteBuf对象，然后将此ByteBuf对象addMessage到buffer环形数组中，
 * 所以其实我们所说的应用与channel之间的写缓冲区指的就是ChannelOutboundBuffer+应用自己创建的ByteBuf对象
 */
public final class ChannelOutboundBuffer {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ChannelOutboundBuffer.class);

    private static final int INITIAL_CAPACITY = 32;

    private static final int threadLocalDirectBufferSize;

    static {
        threadLocalDirectBufferSize = SystemPropertyUtil.getInt("io.netty.threadLocalDirectBufferSize", 64 * 1024);
        logger.debug("-Dio.netty.threadLocalDirectBufferSize: {}", threadLocalDirectBufferSize);
    }

    private static final Recycler<ChannelOutboundBuffer> RECYCLER = new Recycler<ChannelOutboundBuffer>() {
        @Override
        protected ChannelOutboundBuffer newObject(Handle<ChannelOutboundBuffer> handle) {
            return new ChannelOutboundBuffer(handle);
        }
    };

    static ChannelOutboundBuffer newInstance(AbstractChannel channel) {
        // 对象回收池
        ChannelOutboundBuffer buffer = RECYCLER.get();
        buffer.channel = channel;
        buffer.totalPendingSize = 0;
        buffer.writable = 1;
        return buffer;
    }

    private final Handle<ChannelOutboundBuffer> handle;

    private AbstractChannel channel;

    // 果然是环形数组
    // A circular buffer used to store messages.  The buffer is arranged such that:  flushed <= unflushed <= tail.  The
    // flushed messages are stored in the range [flushed, unflushed).  Unflushed messages are stored in the range
    // [unflushed, tail).
    private Entry[]     buffer;
    private int flushed;
    private int unflushed;
    private int tail;

    private ByteBuffer[] nioBuffers;
    private int nioBufferCount;
    private long nioBufferSize;

    private boolean inFail;

    // CAS乐观锁更新totalPendingSize
    private static final AtomicLongFieldUpdater<ChannelOutboundBuffer> TOTAL_PENDING_SIZE_UPDATER =
            AtomicLongFieldUpdater.newUpdater(ChannelOutboundBuffer.class, "totalPendingSize");

    private volatile long totalPendingSize;

    private static final AtomicIntegerFieldUpdater<ChannelOutboundBuffer> WRITABLE_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(ChannelOutboundBuffer.class, "writable");

    private volatile int writable = 1;

    private ChannelOutboundBuffer(Handle<ChannelOutboundBuffer> handle) {
        this.handle = handle;

        buffer = new Entry[INITIAL_CAPACITY];
        for (int i = 0; i < buffer.length; i++) {
            buffer[i] = new Entry();
        }

        nioBuffers = new ByteBuffer[INITIAL_CAPACITY];
    }

    /**
     * 将业务代码的resp加入outboundBuffer
     * @param msg
     * @param promise
     */
    void addMessage(Object msg, ChannelPromise promise) {
        int size = channel.estimatorHandle().size(msg);
        if (size < 0) {
            size = 0;
        }

        Entry e = buffer[tail++];
        e.msg = msg;
        e.pendingSize = size;
        e.promise = promise;
        e.total = total(msg);
        // myConfusionsv:&运算有什么特别之处吗？buffer.length初始值为32 31=11111
        // buffer为环形数组，tail为当前可写入元素的指针。
        // netty中将buffer的初始容量设置为32，后面容量扩张也是32的倍数，所以当tail到达数组末尾时通过&操作就可以将指针重新指到起始位置，如果未到末尾则执行完与操作后tail的值不变，这样就实现了环形队列。
        tail &= buffer.length - 1;

        // myConfusion:tail == flushed代表了啥？
        //  因为addFlush()中unflushed=tail且isEmpty()中unflushed == flushed，所以[flushed, unflushed)中应该是本次flush即将flush的数据，
        // [unflushed, tail)是write了但还未flush的数据，[0,flushed)是本次flush之前已经flush的数据，所以tail == flushed只是说明本次flush将
        // buffer中的所有待flush数据全部flush了而已，但是buffer的容量不一定就不够了，所以为啥要扩容呢？
        if (tail == flushed) {
            addCapacity();
        }

        // increment pending bytes after adding message to the unflushed arrays.
        // See https://github.com/netty/netty/issues/1619
        incrementPendingOutboundBytes(size);
    }

    private void addCapacity() {
        int p = flushed;
        int n = buffer.length;
        int r = n - p; // number of elements to the right of p
        int s = size();

        int newCapacity = n << 1;
        if (newCapacity < 0) {
            throw new IllegalStateException();
        }

        Entry[] e = new Entry[newCapacity];
        System.arraycopy(buffer, p, e, 0, r);
        System.arraycopy(buffer, 0, e, r, p);
        for (int i = n; i < e.length; i++) {
            e[i] = new Entry();
        }

        buffer = e;
        flushed = 0;
        unflushed = s;
        tail = n;
    }

    // 先将unflush指针修改为tail，标识本次发送的范围
    void addFlush() {
        unflushed = tail;
    }

    /**
     * Increment the pending bytes which will be written at some point.
     * This method is thread-safe!
     */
    void incrementPendingOutboundBytes(int size) {
        // Cache the channel and check for null to make sure we not produce a NPE in case of the Channel gets
        // recycled while process this method.
        Channel channel = this.channel;
        if (size == 0 || channel == null) {
            return;
        }

        long oldValue = totalPendingSize;
        long newWriteBufferSize = oldValue + size;
        while (!TOTAL_PENDING_SIZE_UPDATER.compareAndSet(this, oldValue, newWriteBufferSize)) {
            oldValue = totalPendingSize;
            newWriteBufferSize = oldValue + size;
        }

        int highWaterMark = channel.config().getWriteBufferHighWaterMark();

        // 如果待发送的字节数超过了highWaterMark高水位配置，则不能再写到环形数组了
        if (newWriteBufferSize > highWaterMark) {
            if (WRITABLE_UPDATER.compareAndSet(this, 1, 0)) {
                channel.pipeline().fireChannelWritabilityChanged();
            }
        }
    }

    /**
     * Decrement the pending bytes which will be written at some point.
     * This method is thread-safe!
     */
    void decrementPendingOutboundBytes(int size) {
        // Cache the channel and check for null to make sure we not produce a NPE in case of the Channel gets
        // recycled while process this method.
        Channel channel = this.channel;
        if (size == 0 || channel == null) {
            return;
        }

        long oldValue = totalPendingSize;
        long newWriteBufferSize = oldValue - size;
        while (!TOTAL_PENDING_SIZE_UPDATER.compareAndSet(this, oldValue, newWriteBufferSize)) {
            oldValue = totalPendingSize;
            newWriteBufferSize = oldValue - size;
        }

        int lowWaterMark = channel.config().getWriteBufferLowWaterMark();

        // 如果待发送的字节数小于lowWaterMark低水位配置，则可以再次写到环形数组了
        if (newWriteBufferSize == 0 || newWriteBufferSize < lowWaterMark) {
            if (WRITABLE_UPDATER.compareAndSet(this, 0, 1)) {
                channel.pipeline().fireChannelWritabilityChanged();
            }
        }
    }

    private static long total(Object msg) {
        if (msg instanceof ByteBuf) {
            return ((ByteBuf) msg).readableBytes();
        }
        if (msg instanceof FileRegion) {
            return ((FileRegion) msg).count();
        }
        if (msg instanceof ByteBufHolder) {
            return ((ByteBufHolder) msg).content().readableBytes();
        }
        return -1;
    }

    public Object current() {
        return current(true);
    }

    /**
     * myConfusion:在flush之前，addFlush()执行unflushed = tail，这个对之后的flush有何好处？
     * @param preferDirect
     * @return
     */
    public Object current(boolean preferDirect) {
        // 1.判断是否还有需要发送的消息
        if (isEmpty()) {
            return null;
        } else {
            // TODO: Think of a smart way to handle ByteBufHolder messages
            Object msg = buffer[flushed].msg;
            if (threadLocalDirectBufferSize <= 0 || !preferDirect) {
                return msg;
            }
            // 2. 判断msg类型
            if (msg instanceof ByteBuf) {
                ByteBuf buf = (ByteBuf) msg;
                // 2.1 若是ByteBuf且是直接内存则直接返回
                if (buf.isDirect()) {
                    return buf;
                } else {
                    // 2.2 若是堆内存，则判断buf中是否还有可以读出来然后写入channel的数据
                    int readableBytes = buf.readableBytes();
                    if (readableBytes == 0) {
                        return buf;
                    }

                    // Non-direct buffers are copied into JDK's own internal direct buffer on every I/O.
                    // We can do a better job by using our pooled allocator. If the current allocator does not
                    // pool a direct buffer, we use a ThreadLocal based pool.
                    ByteBufAllocator alloc = channel.alloc();
                    ByteBuf directBuf;
                    if (alloc.isDirectBufferPooled()) {
                        directBuf = alloc.directBuffer(readableBytes);
                    } else {
                        directBuf = ThreadLocalPooledByteBuf.newInstance();
                    }
                    directBuf.writeBytes(buf, buf.readerIndex(), readableBytes);
                    current(directBuf);
                    return directBuf;
                }
            }
            return msg;
        }
    }

    /**
     * Replace the current msg with the given one.
     * The replaced msg will automatically be released
     */
    public void current(Object msg) {
        Entry entry =  buffer[flushed];
        safeRelease(entry.msg);
        entry.msg = msg;
    }

    public void progress(long amount) {
        Entry e = buffer[flushed];
        ChannelPromise p = e.promise;
        if (p instanceof ChannelProgressivePromise) {
            long progress = e.progress + amount;
            e.progress = progress;
            ((ChannelProgressivePromise) p).tryProgress(progress, e.total);
        }
    }

    /**
     * 从环形数组中删除msg,会被调用多次
     * @return
     */
    public boolean remove() {
        // 1. 判空,说明channelOutboundBuffer中的数据已被删除完成
        if (isEmpty()) {
            return false;
        }

        // 2. 获取Entry
        Entry e = buffer[flushed];
        Object msg = e.msg;
        if (msg == null) {
            return false;
        }

        ChannelPromise promise = e.promise;
        int size = e.pendingSize;

        // 3.对Entry对象中的成员变量进行释放（设置为null,等待gc）
        e.clear();

        // 4.对需要发送的索引flushed进行更新 myConfusion:写完之后将flushed加1，岂不是说明[flushed,unflushed)之间的是需要flushed的，而不是已经flush的，这个与注释不符
        flushed = flushed + 1 & buffer.length - 1;

        // 5.将msg对象被引用的次数减1
        safeRelease(msg);

        // 6.设置发送成功结果？？？
        // --是的，在write()时（addMessage将数据写入channelOutboundBuffer）每个entry都被设置了promise(write()时生成)，
        // 在这之后执行flush()时（将数据从channelOutboundBuffer写入channel时对设置的promise对象设置result）,这就实现了两个不同操作的结果传递，
        // 如果这两个操作属于不同线程，那么就实现了异步任务的结果传递
        promise.trySuccess();
        // 7.将等待发送的字节数减size（因为已经发送了）
        decrementPendingOutboundBytes(size);

        return true;
    }

    public boolean remove(Throwable cause) {
        if (isEmpty()) {
            return false;
        }

        Entry e = buffer[flushed];
        Object msg = e.msg;
        if (msg == null) {
            return false;
        }

        ChannelPromise promise = e.promise;
        int size = e.pendingSize;

        e.clear();

        flushed = flushed + 1 & buffer.length - 1;

        safeRelease(msg);

        safeFail(promise, cause);
        decrementPendingOutboundBytes(size);

        return true;
    }

    /**
     * Returns an array of direct NIO buffers if the currently pending messages are made of {@link ByteBuf} only.
     * {@code null} is returned otherwise.  If this method returns a non-null array, {@link #nioBufferCount()} and
     * {@link #nioBufferSize()} will return the number of NIO buffers in the returned array and the total number
     * of readable bytes of the NIO buffers respectively.
     * <p>
     * Note that the returned array is reused and thus should not escape
     * {@link AbstractChannel#doWrite(ChannelOutboundBuffer)}.
     * Refer to {@link NioSocketChannel#doWrite(ChannelOutboundBuffer)} for an example.
     * </p>
     */
    public ByteBuffer[] nioBuffers() {
        long nioBufferSize = 0;
        int nioBufferCount = 0;
        final int mask = buffer.length - 1;
        final ByteBufAllocator alloc = channel.alloc();
        ByteBuffer[] nioBuffers = this.nioBuffers;
        Object m;
        int i = flushed;
        while (i != unflushed && (m = buffer[i].msg) != null) {
            if (!(m instanceof ByteBuf)) {
                this.nioBufferCount = 0;
                this.nioBufferSize = 0;
                return null;
            }

            Entry entry = buffer[i];
            ByteBuf buf = (ByteBuf) m;
            final int readerIndex = buf.readerIndex();
            final int readableBytes = buf.writerIndex() - readerIndex;

            if (readableBytes > 0) {
                nioBufferSize += readableBytes;
                int count = entry.count;
                if (count == -1) {
                    entry.count = count = buf.nioBufferCount();
                }
                int neededSpace = nioBufferCount + count;
                if (neededSpace > nioBuffers.length) {
                    this.nioBuffers = nioBuffers = expandNioBufferArray(nioBuffers, neededSpace, nioBufferCount);
                }

                if (buf.isDirect() || threadLocalDirectBufferSize <= 0) {
                    if (count == 1) {
                        ByteBuffer nioBuf = entry.buf;
                        if (nioBuf == null) {
                            // cache ByteBuffer as it may need to create a new ByteBuffer instance if its a
                            // derived buffer
                            entry.buf = nioBuf = buf.internalNioBuffer(readerIndex, readableBytes);
                        }
                        nioBuffers[nioBufferCount ++] = nioBuf;
                    } else {
                        ByteBuffer[] nioBufs = entry.buffers;
                        if (nioBufs == null) {
                            // cached ByteBuffers as they may be expensive to create in terms of Object allocation
                            entry.buffers = nioBufs = buf.nioBuffers();
                        }
                        nioBufferCount = fillBufferArray(nioBufs, nioBuffers, nioBufferCount);
                    }
                } else {
                    nioBufferCount = fillBufferArrayNonDirect(entry, buf, readerIndex,
                            readableBytes, alloc, nioBuffers, nioBufferCount);
                }
            }
            i = i + 1 & mask;
        }
        this.nioBufferCount = nioBufferCount;
        this.nioBufferSize = nioBufferSize;

        return nioBuffers;
    }

    private static int fillBufferArray(ByteBuffer[] nioBufs, ByteBuffer[] nioBuffers, int nioBufferCount) {
        for (ByteBuffer nioBuf: nioBufs) {
            if (nioBuf == null) {
                break;
            }
            nioBuffers[nioBufferCount ++] = nioBuf;
        }
        return nioBufferCount;
    }

    private static int fillBufferArrayNonDirect(Entry entry, ByteBuf buf, int readerIndex, int readableBytes,
                                      ByteBufAllocator alloc, ByteBuffer[] nioBuffers, int nioBufferCount) {
        ByteBuf directBuf;
        if (alloc.isDirectBufferPooled()) {
            directBuf = alloc.directBuffer(readableBytes);
        } else {
            directBuf = ThreadLocalPooledByteBuf.newInstance();
        }
        directBuf.writeBytes(buf, readerIndex, readableBytes);
        buf.release();
        entry.msg = directBuf;
        // cache ByteBuffer
        ByteBuffer nioBuf = entry.buf = directBuf.internalNioBuffer(0, readableBytes);
        entry.count = 1;
        nioBuffers[nioBufferCount ++] = nioBuf;
        return nioBufferCount;
    }

    private static ByteBuffer[] expandNioBufferArray(ByteBuffer[] array, int neededSpace, int size) {
        int newCapacity = array.length;
        do {
            // double capacity until it is big enough
            // See https://github.com/netty/netty/issues/1890
            newCapacity <<= 1;

            if (newCapacity < 0) {
                throw new IllegalStateException();
            }

        } while (neededSpace > newCapacity);

        ByteBuffer[] newArray = new ByteBuffer[newCapacity];
        System.arraycopy(array, 0, newArray, 0, size);

        return newArray;
    }

    public int nioBufferCount() {
        return nioBufferCount;
    }

    public long nioBufferSize() {
        return nioBufferSize;
    }

    boolean getWritable() {
        return writable != 0;
    }

    public int size() {
        return unflushed - flushed & buffer.length - 1;
    }

    public boolean isEmpty() {
        return unflushed == flushed;
    }

    void failFlushed(Throwable cause) {
        // Make sure that this method does not reenter.  A listener added to the current promise can be notified by the
        // current thread in the tryFailure() call of the loop below, and the listener can trigger another fail() call
        // indirectly (usually by closing the channel.)
        //
        // See https://github.com/netty/netty/issues/1501
        if (inFail) {
            return;
        }

        try {
            inFail = true;
            for (;;) {
                if (!remove(cause)) {
                    break;
                }
            }
        } finally {
            inFail = false;
        }
    }

    void close(final ClosedChannelException cause) {
        if (inFail) {
            channel.eventLoop().execute(new Runnable() {
                @Override
                public void run() {
                    close(cause);
                }
            });
            return;
        }

        inFail = true;

        if (channel.isOpen()) {
            throw new IllegalStateException("close() must be invoked after the channel is closed.");
        }

        if (!isEmpty()) {
            throw new IllegalStateException("close() must be invoked after all flushed writes are handled.");
        }

        // Release all unflushed messages.
        final int unflushedCount = tail - unflushed & buffer.length - 1;
        try {
            for (int i = 0; i < unflushedCount; i++) {
                Entry e = buffer[unflushed + i & buffer.length - 1];
                safeRelease(e.msg);
                e.msg = null;
                safeFail(e.promise, cause);
                e.promise = null;

                // Just decrease; do not trigger any events via decrementPendingOutboundBytes()
                int size = e.pendingSize;
                long oldValue = totalPendingSize;
                long newWriteBufferSize = oldValue - size;
                while (!TOTAL_PENDING_SIZE_UPDATER.compareAndSet(this, oldValue, newWriteBufferSize)) {
                    oldValue = totalPendingSize;
                    newWriteBufferSize = oldValue - size;
                }

                e.pendingSize = 0;
            }
        } finally {
            tail = unflushed;
            inFail = false;
        }

        recycle();
    }

    private static void safeRelease(Object message) {
        try {
            ReferenceCountUtil.release(message);
        } catch (Throwable t) {
            logger.warn("Failed to release a message.", t);
        }
    }

    private static void safeFail(ChannelPromise promise, Throwable cause) {
        if (!(promise instanceof VoidChannelPromise) && !promise.tryFailure(cause)) {
            logger.warn("Promise done already: {} - new exception is:", promise, cause);
        }
    }

    public void recycle() {
        if (buffer.length > INITIAL_CAPACITY) {
            Entry[] e = new Entry[INITIAL_CAPACITY];
            System.arraycopy(buffer, 0, e, 0, INITIAL_CAPACITY);
            buffer = e;
        }

        if (nioBuffers.length > INITIAL_CAPACITY) {
            nioBuffers = new ByteBuffer[INITIAL_CAPACITY];
        } else {
            // null out the nio buffers array so the can be GC'ed
            // https://github.com/netty/netty/issues/1763
            Arrays.fill(nioBuffers, null);
        }

        // reset flushed, unflushed and tail
        // See https://github.com/netty/netty/issues/1772
        flushed = 0;
        unflushed = 0;
        tail = 0;

        // Set the channel to null so it can be GC'ed ASAP
        channel = null;

        RECYCLER.recycle(this, handle);
    }

    private static final class Entry {

        // 需要被写出的业务resp
        Object msg;
        // myConfusionsv:属性buffers、buf与msg的关系？
        // --buffers与msg指向同一块内存的字节数组（元素只有一个）；buf与msg指向同一块内存的ByteBuffer对象
        ByteBuffer[] buffers;
        ByteBuffer buf;
        ChannelPromise promise;
        // 此消息写出去的进度
        // myConfusionsv:如果发生了写半包，那当前这个msg中有一部分数据已经被写出去了，按理下一次需要写出去的数据应该是progress之后的，但是没看到progress有这个用途
        // --那是因为ByteBuf写出时会更改readerIndex,所以下次这个buf的可读范围已经排出了当次写出的数据
        long progress;
        // myConfusionsv：total也是msg中可读字节数，与pendingSize区别
        // --pendingSize表示的是可以写到channel的字节数，会变化，比如若channel关闭，那么能够写出的字节数就变为0了
        long total;
        // msg中可读字节数（读出来后写到channel）
        int pendingSize;
        int count = -1;

        public void clear() {
            buffers = null;
            buf = null;
            msg = null;
            promise = null;
            progress = 0;
            total = 0;
            pendingSize = 0;
            count = -1;
        }
    }

    static final class ThreadLocalPooledByteBuf extends UnpooledDirectByteBuf {
        private final Recycler.Handle<ThreadLocalPooledByteBuf> handle;

        private static final Recycler<ThreadLocalPooledByteBuf> RECYCLER = new Recycler<ThreadLocalPooledByteBuf>() {
            @Override
            protected ThreadLocalPooledByteBuf newObject(Handle<ThreadLocalPooledByteBuf> handle) {
                return new ThreadLocalPooledByteBuf(handle);
            }
        };

        private ThreadLocalPooledByteBuf(Recycler.Handle<ThreadLocalPooledByteBuf> handle) {
            super(UnpooledByteBufAllocator.DEFAULT, 256, Integer.MAX_VALUE);
            this.handle = handle;
        }

        static ThreadLocalPooledByteBuf newInstance() {
            ThreadLocalPooledByteBuf buf = RECYCLER.get();
            buf.setRefCnt(1);
            return buf;
        }

        @Override
        protected void deallocate() {
            if (capacity() > threadLocalDirectBufferSize) {
                super.deallocate();
            } else {
                clear();
                RECYCLER.recycle(this, handle);
            }
        }
    }
}
