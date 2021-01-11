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

package io.netty.util;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Light-weight object pool based on a thread-local stack.
 *
 * @param <T> the type of the pooled object
 */
public abstract class Recycler<T> {

    // field，由于Recycler实例是在类加载器件生成的，因此threadLocal对象同样也在类加载期间生成
    private final ThreadLocal<Stack<T>> threadLocal = new ThreadLocal<Stack<T>>() {

        /**
         * 初始数据,在第一次get()时执行
         * @return
         */
        @Override
        protected Stack<T> initialValue() {
            // 创建stack对象
            return new Stack<T>(Recycler.this, Thread.currentThread());
        }
    };

    public final T get() {
        Stack<T> stack = threadLocal.get();
        T o = stack.pop();
        if (o == null) {
            // 第一次stack.pop()返回null
            o = newObject(stack);
        }
        return o;
    }

    public final boolean recycle(T o, Handle<T> handle) {
        @SuppressWarnings("unchecked")
        Stack<T> stack = (Stack<T>) handle;
        if (stack.parent != this) {
            return false;
        }

        if (Thread.currentThread() != stack.thread) {
            return false;
        }

        stack.push(o);
        return true;
    }

    protected abstract T newObject(Handle<T> handle);

    public interface Handle<T> {
        void recycle(T object);
    }

    static final class Stack<T> implements Handle<T> {

        private static final int INITIAL_CAPACITY = 256;

        final Recycler<T> parent;
        final Thread thread;
        private T[] elements;
        private int size;
        private final Map<T, Boolean> map = new IdentityHashMap<T, Boolean>(INITIAL_CAPACITY);

        @SuppressWarnings({ "unchecked", "SuspiciousArrayCast" })
        Stack(Recycler<T> parent, Thread thread) {
            this.parent = parent;
            this.thread = thread;
            // 对象池
            elements = newArray(INITIAL_CAPACITY);
        }

        @Override
        public void recycle(T object) {
            parent.recycle(object, this);
        }

        T pop() {
            int size = this.size;
            if (size == 0) {
                return null;
            }
            size --;
            T ret = elements[size];
            elements[size] = null;
            map.remove(ret);
            this.size = size;
            return ret;
        }

        void push(T o) {
            if (map.put(o, Boolean.TRUE) != null) {
                throw new IllegalStateException("recycled already");
            }

            int size = this.size;
            if (size == elements.length) {
                T[] newElements = newArray(size << 1);
                System.arraycopy(elements, 0, newElements, 0, size);
                elements = newElements;
            }

            elements[size] = o;
            this.size = size + 1;
        }

        @SuppressWarnings({ "unchecked", "SuspiciousArrayCast" })
        private static <T> T[] newArray(int length) {
            return (T[]) new Object[length];
        }
    }
}
