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
package io.netty.util.concurrent;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Abstract {@link Future} implementation which does not allow for cancellation.
 *
 * @param <V>
 */
public abstract class AbstractFuture<V> implements Future<V> {

    /**
     * 获取结果 不支持超时的
     * @return
     * @throws InterruptedException
     * @throws ExecutionException
     */
    @Override
    public V get() throws InterruptedException, ExecutionException {
        // 1. 无限期阻塞，当另一线程IO操作完成后会notify(),本线程会结束阻塞
        await();

        // 2. 判断是否有异常，若无则获取结果，否则抛出异常
        Throwable cause = cause();
        if (cause == null) {
            return getNow();
        }
        throw new ExecutionException(cause);
    }

    /**
     * 获取结果--支持超时
     * @param timeout
     * @param unit
     * @return
     * @throws InterruptedException
     * @throws ExecutionException
     * @throws TimeoutException
     */
    @Override
    public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        // 1. await(timeout, unit)返回false表示在规定时间内操作没有完成
        if (await(timeout, unit)) {
            Throwable cause = cause();
            if (cause == null) {
                return getNow();
            }
            throw new ExecutionException(cause);
        }
        throw new TimeoutException();
    }
}
