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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@link RecvByteBufAllocator} that automatically increases and
 * decreases the predicted buffer size on feed back.
 * <p>
 * It gradually increases the expected number of readable bytes if the previous
 * read fully filled the allocated buffer.  It gradually decreases the expected
 * number of readable bytes if the read operation was not able to fill a certain
 * amount of the allocated buffer two times consecutively.  Otherwise, it keeps
 * returning the same prediction.
 *
 * 缓冲区大小可以动态调整
 */
public class AdaptiveRecvByteBufAllocator implements RecvByteBufAllocator {

    // 三个系统默认值
    // 最小缓冲区长度64Byte
    static final int DEFAULT_MINIMUM = 64;
    // 初始容量1024Byte
    static final int DEFAULT_INITIAL = 1024;
    // 最大缓冲区长度65536Byte
    static final int DEFAULT_MAXIMUM = 65536;

    // 两个动态调整容量（缓冲区大小）时的步进参数
    // 扩张步进索引
    private static final int INDEX_INCREMENT = 4;
    // 收缩步进索引
    private static final int INDEX_DECREMENT = 1;

    // 缓冲区长度向量表（数组），每个元素对应一个缓冲区buffer的容量（多少个字节），
    // 当容量小于512B时，因为缓冲区较小，因此需要较小的步进值（16）；当容量大于512时，说明当前需要读取的数据较大，因此需要较大的步进值减少动态扩张的频率（512的倍数）
    private static final int[] SIZE_TABLE;

    // 初始化SIZE_TABLE
    static {
        List<Integer> sizeTable = new ArrayList<Integer>();
        // 31个
        for (int i = 16; i < 512; i += 16) {
            sizeTable.add(i);
        }

        // 2^9 0...1000000000 左移22次时1到符号位，变为负值，因此总共左移21次，加上初始值512，则共循环22次 22个元素
        for (int i = 512; i > 0; i <<= 1) {
            sizeTable.add(i);
        }

        SIZE_TABLE = new int[sizeTable.size()];
        for (int i = 0; i < SIZE_TABLE.length; i ++) {
            SIZE_TABLE[i] = sizeTable.get(i);
        }
    }

    public static final AdaptiveRecvByteBufAllocator DEFAULT = new AdaptiveRecvByteBufAllocator();

    /**
     * 根据容量size查表容量数组中的对应索引值 二分查找法
     * @param size
     * @return
     */
    private static int getSizeTableIndex(final int size) {
        for (int low = 0, high = SIZE_TABLE.length - 1;;) {
            if (high < low) {
                return low;
            }
            if (high == low) {
                return high;
            }

            int mid = low + high >>> 1;
            int a = SIZE_TABLE[mid];
            int b = SIZE_TABLE[mid + 1];
            if (size > b) {
                low = mid + 1;
            } else if (size < a) {
                high = mid - 1;
            } else if (size == a) {
                return mid;
            } else {
                return mid + 1;
            }
        }
    }

    private static final class HandleImpl implements Handle {
        private final int minIndex;
        private final int maxIndex;
        private int index;
        // 下一次分配的buffer大小
        private int nextReceiveBufferSize;
        // 是否立即执行容量收缩操作
        private boolean decreaseNow;

        HandleImpl(int minIndex, int maxIndex, int initial) {
            this.minIndex = minIndex;
            this.maxIndex = maxIndex;

            index = getSizeTableIndex(initial);
            nextReceiveBufferSize = SIZE_TABLE[index];
        }

        @Override
        public ByteBuf allocate(ByteBufAllocator alloc) {
            return alloc.ioBuffer(nextReceiveBufferSize);
        }

        @Override
        public int guess() {
            return nextReceiveBufferSize;
        }

        /**
         * 当channel本次读取完数据后，会根据实际读的字节数（actualReadBytes），对下次接收byteBuffer进行动态调整
         * @param actualReadBytes the actual number of read bytes in the previous read operation
         */
        @Override
        public void record(int actualReadBytes) {
            // 1. SIZE_TABLE[Math.max(0, index - INDEX_DECREMENT - 1)]:收缩后索引对应的容量，将之与实际字节数进行比较
            // myConfusion:缩减索引index - INDEX_DECREMENT - 1还额外减了1是因为如果actualReadBytes为index - INDEX_DECREMENT对应容量的话，
            //  nextReceiveBufferSize取index相应容量(即下次接收缓冲区稍大于actualReadBytes)是没问题的吧
            if (actualReadBytes <= SIZE_TABLE[Math.max(0, index - INDEX_DECREMENT - 1)]) {
                // 1.1 若实际字节数偏小，则需要对下次读取缓冲区进行缩减
                if (decreaseNow) {
                    index = Math.max(index - INDEX_DECREMENT, minIndex);
                    nextReceiveBufferSize = SIZE_TABLE[index];
                    decreaseNow = false;
                } else {
                    decreaseNow = true;
                }
            } else if (actualReadBytes >= nextReceiveBufferSize) {
                // 2. 实际字节数大于初始容量，则需要对下次读取缓冲区进行扩张
                index = Math.min(index + INDEX_INCREMENT, maxIndex);
                nextReceiveBufferSize = SIZE_TABLE[index];
                decreaseNow = false;
            }
        }
    }

    private final int minIndex;
    private final int maxIndex;
    private final int initial;

    /**
     * Creates a new predictor with the default parameters.  With the default
     * parameters, the expected buffer size starts from {@code 1024}, does not
     * go down below {@code 64}, and does not go up above {@code 65536}.
     */
    private AdaptiveRecvByteBufAllocator() {
        this(DEFAULT_MINIMUM, DEFAULT_INITIAL, DEFAULT_MAXIMUM);
    }

    /**
     * Creates a new predictor with the specified parameters.
     *
     * @param minimum  the inclusive lower bound of the expected buffer size
     * @param initial  the initial buffer size when no feed back was received
     * @param maximum  the inclusive upper bound of the expected buffer size
     */
    public AdaptiveRecvByteBufAllocator(int minimum, int initial, int maximum) {
        if (minimum <= 0) {
            throw new IllegalArgumentException("minimum: " + minimum);
        }
        if (initial < minimum) {
            throw new IllegalArgumentException("initial: " + initial);
        }
        if (maximum < initial) {
            throw new IllegalArgumentException("maximum: " + maximum);
        }

        int minIndex = getSizeTableIndex(minimum);
        if (SIZE_TABLE[minIndex] < minimum) {
            // 此处是sizaTable中没有相应容量值时
            this.minIndex = minIndex + 1;
        } else {
            this.minIndex = minIndex;
        }

        int maxIndex = getSizeTableIndex(maximum);
        if (SIZE_TABLE[maxIndex] > maximum) {
            this.maxIndex = maxIndex - 1;
        } else {
            this.maxIndex = maxIndex;
        }

        this.initial = initial;
    }

    @Override
    public Handle newHandle() {
        return new HandleImpl(minIndex, maxIndex, initial);
    }
}
