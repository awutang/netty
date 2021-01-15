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

import io.netty.util.ReferenceCounted;

/**
 * A packet which is send or receive.
 *
 * 1.ByteBufHolder是ByteBuf的容器，在Netty中很有用，例如http协议的请求和应答消息都可以携带消息体
 * 这个消息体在Java nio中是ByteBuffer对象，在netty中是ByteBuf对象，但是传输不一定只用http协议，
 * 如果是其他协议，那必然有与http不同的协议字段和功能，因此需要对ByteBuf进行封装和抽象，不同的子类有不同的协议实现。
 */
public interface ByteBufHolder extends ReferenceCounted {

    /**
     * Return the data which is held by this {@link ByteBufHolder}.
     */
    ByteBuf content();

    /**
     * Create a deep copy of this {@link ByteBufHolder}.
     */
    ByteBufHolder copy();

    /**
     * Duplicate the {@link ByteBufHolder}. Be aware that this will not automatically call {@link #retain()}.
     */
    ByteBufHolder duplicate();

    @Override
    ByteBufHolder retain();

    @Override
    ByteBufHolder retain(int increment);
}
