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

package io.netty.buffer;

/**
 * 1.一个Page只能用于分配与第一次申请分配时相同的内存块，比如一个4B的Page,第一次分配1B，后续只能继续分配1B，
 * 如果应用此时需要申请2B则需要用到另一个Page
 * 2.Page中存储区域的使用状态通过一个long[]来维护，数组中每个long的每一个位代表一个块存储区域的占用情况：0表示未占用，1表示已占用
 *  例如，对于一个128B的page,long[]有两个long,共128位来表示这128B的分配情况；对于一个4B的page,只需要4位那么就只需要
 *  1个long（用这个long的低四位）,即long[]中只有一个元素
 * 这两个例子的场景是page中每次是分配一个Byte。如果一个128B的page中每次是分配2B,那只需要64位来表示这128B的分配情况。
 */
final class PoolSubpage<T> {

    final PoolChunk<T> chunk;
    final int memoryMapIdx;
    final int runOffset;
    final int pageSize;

    // 每个位表示块是否被分配
    final long[] bitmap;

    PoolSubpage<T> prev;
    PoolSubpage<T> next;

    boolean doNotDestroy;
    int elemSize;
    int maxNumElems;
    int nextAvail;
    int bitmapLength;
    int numAvail;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /** Special constructor that creates a linked list head */
    PoolSubpage(int pageSize) {
        chunk = null;
        memoryMapIdx = -1;
        runOffset = -1;
        elemSize = -1;
        this.pageSize = pageSize;
        bitmap = null;
    }

    PoolSubpage(PoolChunk<T> chunk, int memoryMapIdx, int runOffset, int pageSize, int elemSize) {
        this.chunk = chunk;
        this.memoryMapIdx = memoryMapIdx;
        this.runOffset = runOffset;
        this.pageSize = pageSize;
        bitmap = new long[pageSize >>> 10]; // pageSize / 16 / 64
        init(elemSize);
    }

    void init(int elemSize) {
        doNotDestroy = true;
        this.elemSize = elemSize;
        if (elemSize != 0) {
            maxNumElems = numAvail = pageSize / elemSize;
            nextAvail = 0;
            bitmapLength = maxNumElems >>> 6;
            if ((maxNumElems & 63) != 0) {
                bitmapLength ++;
            }

            for (int i = 0; i < bitmapLength; i ++) {
                bitmap[i] = 0;
            }
        }

        addToPool();
    }

    /**
     * Returns the bitmap index of the subpage allocation.
     */
    long allocate() {
        if (elemSize == 0) {
            return toHandle(0);
        }

        if (numAvail == 0 || !doNotDestroy) {
            return -1;
        }

        final int bitmapIdx = nextAvail;
        int q = bitmapIdx >>> 6;
        int r = bitmapIdx & 63;
        assert (bitmap[q] >>> r & 1) == 0;
        bitmap[q] |= 1L << r;

        if (-- numAvail == 0) {
            removeFromPool();
            nextAvail = -1;
        } else {
            nextAvail = findNextAvailable();
        }

        return toHandle(bitmapIdx);
    }

    /**
     * @return {@code true} if this subpage is in use.
     *         {@code false} if this subpage is not used by its chunk and thus it's OK to be released.
     */
    boolean free(int bitmapIdx) {

        if (elemSize == 0) {
            return true;
        }

        int q = bitmapIdx >>> 6;
        int r = bitmapIdx & 63;
        assert (bitmap[q] >>> r & 1) != 0;
        bitmap[q] ^= 1L << r;

        if (numAvail ++ == 0) {
            nextAvail = bitmapIdx;
            addToPool();
            return true;
        }

        if (numAvail != maxNumElems) {
            return true;
        } else {
            // Subpage not in use (numAvail == maxNumElems)
            if (prev == next) {
                // Do not remove if this subpage is the only one left in the pool.
                return true;
            }

            // Remove this subpage from the pool if there are other subpages left in the pool.
            doNotDestroy = false;
            removeFromPool();
            return false;
        }
    }

    private void addToPool() {
        PoolSubpage<T> head = chunk.arena.findSubpagePoolHead(elemSize);
        assert prev == null && next == null;
        prev = head;
        next = head.next;
        next.prev = this;
        head.next = this;
    }

    private void removeFromPool() {
        assert prev != null && next != null;
        prev.next = next;
        next.prev = prev;
        next = null;
        prev = null;
    }

    private int findNextAvailable() {
        int newNextAvail = -1;
        loop:
        for (int i = 0; i < bitmapLength; i ++) {
            long bits = bitmap[i];
            if (~bits != 0) {
                for (int j = 0; j < 64; j ++) {
                    if ((bits & 1) == 0) {
                        newNextAvail = i << 6 | j;
                        break loop;
                    }
                    bits >>>= 1;
                }
            }
        }

        if (newNextAvail < maxNumElems) {
            return newNextAvail;
        } else {
            return -1;
        }
    }

    private long toHandle(int bitmapIdx) {
        return 0x4000000000000000L | (long) bitmapIdx << 32 | memoryMapIdx;
    }

    public String toString() {
        if (!doNotDestroy) {
            return "(" + memoryMapIdx + ": not in use)";
        }

        return String.valueOf('(') + memoryMapIdx + ": " + (maxNumElems - numAvail) + '/' + maxNumElems +
               ", offset: " + runOffset + ", length: " + pageSize + ", elemSize: " + elemSize + ')';
    }
}
