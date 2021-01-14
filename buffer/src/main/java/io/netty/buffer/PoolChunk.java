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
 * 1.组织和管理多个Page的分配和释放
 * 2.netty中，Chunk中的Page被构建成一颗平衡二叉树，叶子节点才是真正的一个Page,两个叶子节点对应的父节点只是代表了两个Page而已
 *      2.1 对tree的遍历采用深度优先搜索算法，但在选择哪个子节点继续遍历时是随机的，并不总是遍历到左子节点
 *      2.2 chunk通过标识位表示内存是否已分配，标识位在节点Page上;Page上也是通过标识位表示内存分配情况的，但是标识位在一个表示多个块是否分配的long[]上
 */
final class PoolChunk<T> {
    private static final int ST_UNUSED = 0;
    private static final int ST_BRANCH = 1;
    private static final int ST_ALLOCATED = 2;
    private static final int ST_ALLOCATED_SUBPAGE = ST_ALLOCATED | 1;

    private static final long multiplier = 0x5DEECE66DL;
    private static final long addend = 0xBL;
    private static final long mask = (1L << 48) - 1;

    // 内存中的一大块连续区域
    final PoolArena<T> arena;
    final T memory;
    final boolean unpooled;


    // 表示一颗平衡满二叉树？--是的，数组的每个元素的值代表完全平衡二叉树节点内存分配情况。
    // 数组的使用域从index = 1开始，将平衡树按照层次顺序依次存储在数组中，depth = n的第1个节点保存在memoryMap[2^n] 中，第2个节点保存在memoryMap[2^n+1]中
    // memoryMap[id] 的值 x，代表id的子节点中，第一个空闲节点位于深度x，在深度[depth_of_id, x)的范围内没有任何空闲节点
    /**
     * memoryMap[id] = depth_of_id：id节点空闲， 初始状态，depth_of_id的值代表id节点在树中的深度，从1开始的
     * memoryMap[id] = maxOrder + 1：id节点全部已使用，节点内存已完全分配，没有一个子节点空闲 maxOrder树的最大深度，默认为11
     * depth_of_id < memoryMap[id] < maxOrder + 1：id节点部分已使用，memoryMap[id] 的值 x，代表id的子节点中，第一个空闲节点位于深度x，
     * 在深度[depth_of_id, x)的范围内没有任何空闲节点
     * 1.index为id的节点对应的memoryMap[id]值是怎么随着当前节点的子节点对应内存分配流转的？
     * --找到能够分配的节点时，更新当前节点值为memoryMap[id] = max_order + 1，代表节点已使用，并遍历当前节点的所有祖先节点，
     * 更新节点值为各自的左右子节点值的最小值。
     * 2.但是当前节点的子节点的memoryMap[id]并没有更新，那么是如何保证这些子节点不会再次被分配的呢？
     * --因为算法是从上往下遍历的，所以实际处理中只更新了祖先节点的值，并没有更新子节点的值
     */
    private final int[] memoryMap;

    // 一个Chunk由一个或多个Page组成？PoolSubpage这是代表一个Page吗？
    // --一个Chunk是由一个或多个Page组成，PoolSubpage代表一个Page的子内存块
    private final PoolSubpage<T>[] subpages;
    /** Used to determine if the requested capacity is equal to or greater than pageSize. */
    private final int subpageOverflowMask;
    private final int pageSize;
    private final int pageShifts;

    private final int chunkSize;
    private final int maxSubpageAllocs;

    private long random = (System.nanoTime() ^ multiplier) & mask;

    private int freeBytes;

    PoolChunkList<T> parent;
    // 前后指针，组成PoolChunkList
    PoolChunk<T> prev;
    PoolChunk<T> next;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    PoolChunk(PoolArena<T> arena, T memory, int pageSize, int maxOrder, int pageShifts, int chunkSize) {
        unpooled = false;
        this.arena = arena;
        this.memory = memory;
        this.pageSize = pageSize;
        this.pageShifts = pageShifts;
        this.chunkSize = chunkSize;
        subpageOverflowMask = ~(pageSize - 1);
        freeBytes = chunkSize;

        int chunkSizeInPages = chunkSize >>> pageShifts;
        maxSubpageAllocs = 1 << maxOrder;

        // Generate the memory map.
        memoryMap = new int[maxSubpageAllocs << 1];
        int memoryMapIndex = 1;
        for (int i = 0; i <= maxOrder; i ++) {
            int runSizeInPages = chunkSizeInPages >>> i;
            for (int j = 0; j < chunkSizeInPages; j += runSizeInPages) {
                //noinspection PointlessBitwiseExpression
                memoryMap[memoryMapIndex ++] = j << 17 | runSizeInPages << 2 | ST_UNUSED;
            }
        }

        subpages = newSubpageArray(maxSubpageAllocs);
    }

    /** Creates a special chunk that is not pooled. */
    PoolChunk(PoolArena<T> arena, T memory, int size) {
        unpooled = true;
        this.arena = arena;
        this.memory = memory;
        memoryMap = null;
        subpages = null;
        subpageOverflowMask = 0;
        pageSize = 0;
        pageShifts = 0;
        chunkSize = size;
        maxSubpageAllocs = 0;
    }

    @SuppressWarnings("unchecked")
    private PoolSubpage<T>[] newSubpageArray(int size) {
        return new PoolSubpage[size];
    }

    int usage() {
        if (freeBytes == 0) {
            return 100;
        }

        int freePercentage = (int) (freeBytes * 100L / chunkSize);
        if (freePercentage == 0) {
            return 99;
        }
        return 100 - freePercentage;
    }

    long allocate(int normCapacity) {
        int firstVal = memoryMap[1];
        if ((normCapacity & subpageOverflowMask) != 0) { // >= pageSize
            return allocateRun(normCapacity, 1, firstVal);
        } else {
            return allocateSubpage(normCapacity, 1, firstVal);
        }
    }

    /**
     * 分配内存：
     * 当分配已归一化（向上取整到2的次方（pageSize=8KB））处理后大小为chunkSize/2^d的内存，即需要在depth = d的层级中找到第一块空闲内存，
     * 算法从根节点开始遍历 (根节点depth = 0， id = 1)，具体步骤如下：
     *  步骤1 判断是否当前节点值memoryMap[id] > d，或depth_of_id > d
     * 如果是，则无法从该chunk分配内存，查找结束
     *  步骤2 判断是否节点值memoryMap[id] == d，且depth_of_id <= d
     * 如果是，当前节点是depth = d的空闲内存，查找结束，更新当前节点值为memoryMap[id] = max_order + 1，
     * 代表节点已使用，并遍历当前节点的所有祖先节点，更新节点值为各自的左右子节点值的最小值；如果否，执行步骤3
     *  步骤3 判断是否当前节点值memoryMap[id] <= d，且depth_of_id <= d
     * 如果是，则空闲节点在当前节点的子节点中，则先判断左子节点memoryMap[2 * id] <=d(判断左子节点是否可分配)，
     * 如果成立，则当前节点更新为左子节点，否则更新为右子节点，然后重复步骤1, 2
     *
     * @param normCapacity
     * @param curIdx
     * @param val
     * @return
     */
    private long allocateRun(int normCapacity, int curIdx, int val) {
        for (;;) {
            if ((val & ST_ALLOCATED) != 0) { // state == ST_ALLOCATED || state == ST_ALLOCATED_SUBPAGE
                return -1;
            }

            if ((val & ST_BRANCH) != 0) { // state == ST_BRANCH
                int nextIdx = curIdx << 1 ^ nextRandom();
                long res = allocateRun(normCapacity, nextIdx, memoryMap[nextIdx]);
                if (res > 0) {
                    return res;
                }

                curIdx = nextIdx ^ 1;
                val = memoryMap[curIdx];
                continue;
            }

            // state == ST_UNUSED
            return allocateRunSimple(normCapacity, curIdx, val);
        }
    }

    private long allocateRunSimple(int normCapacity, int curIdx, int val) {
        int runLength = runLength(val);
        if (normCapacity > runLength) {
            return -1;
        }

        for (;;) {
            if (normCapacity == runLength) {
                // Found the run that fits.
                // Note that capacity has been normalized already, so we don't need to deal with
                // the values that are not power of 2.
                memoryMap[curIdx] = val & ~3 | ST_ALLOCATED;
                freeBytes -= runLength;
                return curIdx;
            }

            int nextIdx = curIdx << 1 ^ nextRandom();
            int unusedIdx = nextIdx ^ 1;

            memoryMap[curIdx] = val & ~3 | ST_BRANCH;
            //noinspection PointlessBitwiseExpression
            memoryMap[unusedIdx] = memoryMap[unusedIdx] & ~3 | ST_UNUSED;

            runLength >>>= 1;
            curIdx = nextIdx;
            val = memoryMap[curIdx];
        }
    }

    private long allocateSubpage(int normCapacity, int curIdx, int val) {
        int state = val & 3;
        if (state == ST_BRANCH) {
            int nextIdx = curIdx << 1 ^ nextRandom();
            long res = branchSubpage(normCapacity, nextIdx);
            if (res > 0) {
                return res;
            }

            return branchSubpage(normCapacity, nextIdx ^ 1);
        }

        if (state == ST_UNUSED) {
            return allocateSubpageSimple(normCapacity, curIdx, val);
        }

        if (state == ST_ALLOCATED_SUBPAGE) {
            PoolSubpage<T> subpage = subpages[subpageIdx(curIdx)];
            int elemSize = subpage.elemSize;
            if (normCapacity != elemSize) {
                return -1;
            }

            return subpage.allocate();
        }

        return -1;
    }

    private long allocateSubpageSimple(int normCapacity, int curIdx, int val) {
        int runLength = runLength(val);
        for (;;) {
            if (runLength == pageSize) {
                memoryMap[curIdx] = val & ~3 | ST_ALLOCATED_SUBPAGE;
                freeBytes -= runLength;

                int subpageIdx = subpageIdx(curIdx);
                PoolSubpage<T> subpage = subpages[subpageIdx];
                if (subpage == null) {
                    subpage = new PoolSubpage<T>(this, curIdx, runOffset(val), pageSize, normCapacity);
                    subpages[subpageIdx] = subpage;
                } else {
                    subpage.init(normCapacity);
                }
                return subpage.allocate();
            }

            int nextIdx = curIdx << 1 ^ nextRandom();
            int unusedIdx = nextIdx ^ 1;

            memoryMap[curIdx] = val & ~3 | ST_BRANCH;
            //noinspection PointlessBitwiseExpression
            memoryMap[unusedIdx] = memoryMap[unusedIdx] & ~3 | ST_UNUSED;

            runLength >>>= 1;
            curIdx = nextIdx;
            val = memoryMap[curIdx];
        }
    }

    private long branchSubpage(int normCapacity, int nextIdx) {
        int nextVal = memoryMap[nextIdx];
        if ((nextVal & 3) != ST_ALLOCATED) {
            return allocateSubpage(normCapacity, nextIdx, nextVal);
        }
        return -1;
    }

    void free(long handle) {
        int memoryMapIdx = (int) handle;
        int bitmapIdx = (int) (handle >>> 32);

        int val = memoryMap[memoryMapIdx];
        int state = val & 3;
        if (state == ST_ALLOCATED_SUBPAGE) {
            assert bitmapIdx != 0;
            PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)];
            assert subpage != null && subpage.doNotDestroy;
            if (subpage.free(bitmapIdx & 0x3FFFFFFF)) {
                return;
            }
        } else {
            assert state == ST_ALLOCATED : "state: " + state;
            assert bitmapIdx == 0;
        }

        freeBytes += runLength(val);

        for (;;) {
            //noinspection PointlessBitwiseExpression
            memoryMap[memoryMapIdx] = val & ~3 | ST_UNUSED;
            if (memoryMapIdx == 1) {
                assert freeBytes == chunkSize;
                return;
            }

            if ((memoryMap[siblingIdx(memoryMapIdx)] & 3) != ST_UNUSED) {
                break;
            }

            memoryMapIdx = parentIdx(memoryMapIdx);
            val = memoryMap[memoryMapIdx];
        }
    }

    void initBuf(PooledByteBuf<T> buf, long handle, int reqCapacity) {
        int memoryMapIdx = (int) handle;
        int bitmapIdx = (int) (handle >>> 32);
        if (bitmapIdx == 0) {
            int val = memoryMap[memoryMapIdx];
            assert (val & 3) == ST_ALLOCATED : String.valueOf(val & 3);
            buf.init(this, handle, runOffset(val), reqCapacity, runLength(val));
        } else {
            initBufWithSubpage(buf, handle, bitmapIdx, reqCapacity);
        }
    }

    void initBufWithSubpage(PooledByteBuf<T> buf, long handle, int reqCapacity) {
        initBufWithSubpage(buf, handle, (int) (handle >>> 32), reqCapacity);
    }

    private void initBufWithSubpage(PooledByteBuf<T> buf, long handle, int bitmapIdx, int reqCapacity) {
        assert bitmapIdx != 0;

        int memoryMapIdx = (int) handle;
        int val = memoryMap[memoryMapIdx];
        assert (val & 3) == ST_ALLOCATED_SUBPAGE;

        PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)];
        assert subpage.doNotDestroy;
        assert reqCapacity <= subpage.elemSize;

        buf.init(
                this, handle,
                runOffset(val) + (bitmapIdx & 0x3FFFFFFF) * subpage.elemSize, reqCapacity, subpage.elemSize);
    }

    private static int parentIdx(int memoryMapIdx) {
        return memoryMapIdx >>> 1;
    }

    private static int siblingIdx(int memoryMapIdx) {
        return memoryMapIdx ^ 1;
    }

    private int runLength(int val) {
        return (val >>> 2 & 0x7FFF) << pageShifts;
    }

    private int runOffset(int val) {
        return val >>> 17 << pageShifts;
    }

    private int subpageIdx(int memoryMapIdx) {
        return memoryMapIdx - maxSubpageAllocs;
    }

    private int nextRandom() {
        random = random * multiplier + addend & mask;
        return (int) (random >>> 47) & 1;
    }

    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("Chunk(");
        buf.append(Integer.toHexString(System.identityHashCode(this)));
        buf.append(": ");
        buf.append(usage());
        buf.append("%, ");
        buf.append(chunkSize - freeBytes);
        buf.append('/');
        buf.append(chunkSize);
        buf.append(')');
        return buf.toString();
    }
}
