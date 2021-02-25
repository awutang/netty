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

package io.netty.buffer;

import io.netty.util.IllegalReferenceCountException;
import io.netty.util.internal.PlatformDependent;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

/**
 * Abstract base class for {@link ByteBuf} implementations that count references.
 *
 * 对对象被引用的次数进行计数，类似gc回收的对象引用计数器，跟踪对象的分配与销毁，做到自动回收内存
 *
 * myConfusionsv:对于heap来说，引用计数能减少gc的频率吗？--其实并不能，heap回收是把array(byte[])置为null,还是依赖gc来做回收的,但是整理来看，
 * directBuffer与内存池(内存池中也有heap)分担了heap压力(减少jvm gc频率，因为如果有一部分堆外内存来分担所需空间需求的话，
 * 就能让堆内内存维持一个较小的范围，从而gc的开销变少（因为gc是需要操作对象的比如复制等等，当对象数量少时，复制也越快）且堆上内存没那么快不够用（减少触发gc的频率），
 * 减少gc对应用的影响)
 * 1.为什么要有引用计数器
 *
 * Netty里四种主力的ByteBuf，
 *
 * 其中UnpooledHeapByteBuf 底下的byte[]能够依赖JVM GC自然回收;而UnpooledDirectByteBuf底下是DirectByteBuffer，如Java堆外内存扫盲贴所述，
 * 除了等JVM GC，最好也能主动进行回收;而PooledHeapByteBuf 和 PooledDirectByteBuf，则必须要主动将用完的byte[]/ByteBuffer放回池里，
 * 如果不释放,内存池会越来越大,直到内存溢出。所以，Netty ByteBuf需要在JVM的GC机制之外，有自己的引用计数器和回收过程(主要是回收到netty申请的内存池)。
 *
 * ByteBuf 该如何选择: 一般业务数据的内存分配选用Java堆内存,回收快;
 * 对于I/O数据的内存分配一般选用池化的直接内存,避免Java堆内存到直接内存的拷贝.
 * 切记自己分配的内存一定要在用完后手动释放.
 *
 */
public abstract class AbstractReferenceCountedByteBuf extends AbstractByteBuf {

    // AtomicIntegerFieldUpdater类通过原子方式对成员变量进行更新，以实无锁化的现线程安全，此refCntUpdater用来原子更新refCnt
    private static final AtomicIntegerFieldUpdater<AbstractReferenceCountedByteBuf> refCntUpdater =
            AtomicIntegerFieldUpdater.newUpdater(AbstractReferenceCountedByteBuf.class, "refCnt");

    // 用于标识refCnt在AbstractReferenceCountedByteBuf中的内存地址
    private static final long REFCNT_FIELD_OFFSET;

    static {
        long refCntFieldOffset = -1;
        try {
            // PlatformDependent.hasUnsafe():sun.misc.Unsafe能被找到用于直接内存加速访问,
            // 因为是直接内存，因此UnpooledUnsafeDirectByteBuf和PooledUnsafeDirectByteBuf才使用到这个字段REFCNT_FIELD_OFFSET
            if (PlatformDependent.hasUnsafe()) {
                // Report the location of a given static field, in conjunction with {@link
                //     * #staticFieldBase}.
                refCntFieldOffset = PlatformDependent.objectFieldOffset(
                        AbstractReferenceCountedByteBuf.class.getDeclaredField("refCnt"));
            }
        } catch (Throwable t) {
            // Ignored
        }

        REFCNT_FIELD_OFFSET = refCntFieldOffset;
    }

    // 对象被引用的次数 volatile为了多线程可见性 初始值为1
    @SuppressWarnings("FieldMayBeFinal")
    private volatile int refCnt = 1;

    protected AbstractReferenceCountedByteBuf(int maxCapacity) {
        super(maxCapacity);
    }

    @Override
    public final int refCnt() {
        if (REFCNT_FIELD_OFFSET >= 0) {
            // Try to do non-volatile read for performance.
            return PlatformDependent.getInt(this, REFCNT_FIELD_OFFSET);
        } else {
            return refCnt;
        }
    }

    /**
     * An unsafe operation intended for use by a subclass that sets the reference count of the buffer directly
     */
    protected final void setRefCnt(int refCnt) {
        this.refCnt = refCnt;
    }

    /**
     * CAS加循环实现自旋锁，保证线程安全
     * @return
     */
    @Override
    public ByteBuf retain() {
        for (;;) {
            int refCnt = this.refCnt;
            // 因为refCnt初始值为1，且retain时加1，release时减1，如果对象被正常地retain和release，那么refCnt的最小值也应该是1，
            // 所以若值为0，说明对象被错误地引用了，需要抛出异常
            if (refCnt == 0) {
                throw new IllegalReferenceCountException(0, 1);
            }
            if (refCnt == Integer.MAX_VALUE) {
                throw new IllegalReferenceCountException(Integer.MAX_VALUE, 1);
            }
            if (refCntUpdater.compareAndSet(this, refCnt, refCnt + 1)) {
                break;
            }
        }
        return this;
    }

    @Override
    public ByteBuf retain(int increment) {
        if (increment <= 0) {
            throw new IllegalArgumentException("increment: " + increment + " (expected: > 0)");
        }

        for (;;) {
            int refCnt = this.refCnt;
            if (refCnt == 0) {
                throw new IllegalReferenceCountException(0, increment);
            }
            if (refCnt > Integer.MAX_VALUE - increment) {
                throw new IllegalReferenceCountException(refCnt, increment);
            }
            if (refCntUpdater.compareAndSet(this, refCnt, refCnt + increment)) {
                break;
            }
        }
        return this;
    }

    @Override
    public final boolean release() {
        for (;;) {
            int refCnt = this.refCnt;
            if (refCnt == 0) {
                throw new IllegalReferenceCountException(0, -1);
            }

            if (refCntUpdater.compareAndSet(this, refCnt, refCnt - 1)) {
                // 说明对象被retain和release的次数相等即对象不被引用，可以被回收了
                if (refCnt == 1) {
                    // 垃圾回收，若是heap则引用置为null(最终依靠gc)；若是direct则用Cleaner回收
                    deallocate();
                    return true;
                }
                return false;
            }
        }
    }

    @Override
    public final boolean release(int decrement) {
        if (decrement <= 0) {
            throw new IllegalArgumentException("decrement: " + decrement + " (expected: > 0)");
        }

        for (;;) {
            int refCnt = this.refCnt;
            if (refCnt < decrement) {
                throw new IllegalReferenceCountException(refCnt, -decrement);
            }

            if (refCntUpdater.compareAndSet(this, refCnt, refCnt - decrement)) {
                if (refCnt == decrement) {
                    deallocate();
                    return true;
                }
                return false;
            }
        }
    }

    /**
     * Called once {@link #refCnt()} is equals 0.
     */
    protected abstract void deallocate();
}
