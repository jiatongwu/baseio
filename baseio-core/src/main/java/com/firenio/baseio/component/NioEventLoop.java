/*
 * Copyright 2015 The Baseio Project
 *  
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *  
 *      http://www.apache.org/licenses/LICENSE-2.0
 *  
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.firenio.baseio.component;

import static com.firenio.baseio.Develop.printException;

import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import com.firenio.baseio.Develop;
import com.firenio.baseio.Options;
import com.firenio.baseio.buffer.ByteBuf;
import com.firenio.baseio.buffer.ByteBufAllocator;
import com.firenio.baseio.collection.ArrayListStack;
import com.firenio.baseio.collection.Attributes;
import com.firenio.baseio.collection.DelayedQueue;
import com.firenio.baseio.collection.DelayedQueue.DelayTask;
import com.firenio.baseio.collection.IntMap;
import com.firenio.baseio.collection.LinkedBQStack;
import com.firenio.baseio.collection.Stack;
import com.firenio.baseio.common.ByteUtil;
import com.firenio.baseio.common.Unsafe;
import com.firenio.baseio.common.Util;
import com.firenio.baseio.component.Channel.EpollChannelUnsafe;
import com.firenio.baseio.component.Channel.JavaChannelUnsafe;
import com.firenio.baseio.component.ChannelAcceptor.EpollAcceptorUnsafe;
import com.firenio.baseio.component.ChannelAcceptor.JavaAcceptorUnsafe;
import com.firenio.baseio.component.ChannelConnector.EpollConnectorUnsafe;
import com.firenio.baseio.component.ChannelConnector.JavaConnectorUnsafe;
import com.firenio.baseio.concurrent.EventLoop;
import com.firenio.baseio.log.Logger;
import com.firenio.baseio.log.LoggerFactory;

/**
 * @author wangkai
 *
 */
public final class NioEventLoop extends EventLoop implements Attributes {

    private static final boolean          CHANNEL_READ_FIRST = Options.isChannelReadFirst();
    private static final Logger           logger             = newLogger();
    private static final IOException      NOT_FINISH_CONNECT = NOT_FINISH_CONNECT();
    private static final IOException      OVER_CH_SIZE_LIMIT = OVER_CH_SIZE_LIMIT();
    private static final boolean          USE_HAS_TASK       = true;

    private final ByteBufAllocator        alloc;
    private final Map<Object, Object>     attributes         = new HashMap<>();
    private final ByteBuf                 buf;
    private final IntMap<Channel>         channels           = new IntMap<>(4096);
    private final int                     chSizeLimit;
    private final DelayedQueue            delayedQueue       = new DelayedQueue();
    private final BlockingQueue<Runnable> events             = new LinkedBlockingQueue<>();
    private final NioEventLoopGroup       group;
    private volatile boolean              hasTask            = false;
    private final int                     index;
    private long                          lastIdleTime       = 0;
    private final AtomicInteger           selecting          = new AtomicInteger();
    private final boolean                 sharable;
    private final NioEventLoopUnsafe      unsafe;
    private final AtomicInteger           wakener            = new AtomicInteger();
    private final long                    bufAddress;
    private final boolean                 acceptor;

    NioEventLoop(NioEventLoopGroup group, int index, String threadName) throws IOException {
        super(threadName);
        this.index = index;
        this.group = group;
        this.sharable = group.isSharable();
        this.acceptor = group.isAcceptor();
        this.alloc = group.getNextByteBufAllocator(index);
        this.chSizeLimit = group.getChannelSizeLimit();
        this.buf = ByteBuf.direct(group.getChannelReadBuffer());
        this.bufAddress = Unsafe.address(buf.getNioBuffer());
        if (Native.EPOLL_AVAIABLE) {
            this.unsafe = new EpollNioEventLoopUnsafe(this);
        } else {
            this.unsafe = new JavaNioEventLoopUnsafe(this);
        }
    }

    public ByteBufAllocator alloc() {
        return alloc;
    }

    @Override
    public Map<Object, Object> attributes() {
        return attributes;
    }

    private void channelIdle(ChannelIdleListener l, Channel ch, long lastIdleTime,
            long currentTime) {
        try {
            l.channelIdled(ch, lastIdleTime, currentTime);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    private void channelIdle(long currentTime) {
        long lastIdleTime = this.lastIdleTime;
        this.lastIdleTime = currentTime;
        IntMap<Channel> channels = this.channels;
        if (channels.isEmpty()) {
            return;
        }
        //FIXME ..optimize sharable group
        if (sharable) {
            for (channels.scan(); channels.hasNext();) {
                Channel ch = channels.nextValue();
                ChannelContext context = ch.getContext();
                List<ChannelIdleListener> ls = context.getChannelIdleEventListeners();
                if (ls.size() == 1) {
                    channelIdle(ls.get(0), ch, lastIdleTime, currentTime);
                } else {
                    for (ChannelIdleListener l : ls) {
                        channelIdle(l, ch, lastIdleTime, currentTime);
                    }
                }
            }
        } else {
            ChannelContext context = group.getContext();
            List<ChannelIdleListener> ls = context.getChannelIdleEventListeners();
            for (ChannelIdleListener l : ls) {
                for (channels.scan(); channels.hasNext();) {
                    Channel ch = channels.nextValue();
                    channelIdle(l, ch, lastIdleTime, currentTime);
                }
            }
        }
    }

    @Override
    public void clearAttributes() {
        this.attributes.clear();
    }

    private void closeChannels() {
        for (channels.scan(); channels.hasNext();) {
            Util.close(channels.nextValue());
        }
    }

    protected long getBufAddress() {
        return bufAddress;
    }

    @Override
    public Object getAttribute(Object key) {
        return this.attributes.get(key);
    }

    @Override
    public Set<Object> getAttributeNames() {
        return this.attributes.keySet();
    }

    public Channel getChannel(int channelId) {
        return channels.get(channelId);
    }

    @SuppressWarnings("unchecked")
    public Stack<Frame> getFrameBuffer(String key, int max) {
        Stack<Frame> buffer = (Stack<Frame>) getAttribute(key);
        if (buffer == null) {
            if (group.isConcurrentFrameStack()) {
                buffer = new LinkedBQStack<>(max);
            } else {
                buffer = new ArrayListStack<>(max);
            }
            setAttribute(key, buffer);
        }
        return buffer;
    }

    public Frame getFrameFromBuffer(Channel ch, String key, int max) {
        return getFrameBuffer(key, max).pop();
    }

    @Override
    public NioEventLoopGroup getGroup() {
        return group;
    }

    public int getIndex() {
        return index;
    }

    @Override
    public BlockingQueue<Runnable> getJobs() {
        return events;
    }

    protected ByteBuf getReadBuf() {
        return buf;
    }

    protected NioEventLoopUnsafe getUnsafe() {
        return unsafe;
    }

    @SuppressWarnings("unchecked")
    public void releaseFrame(String key, Frame frame) {
        Stack<Frame> buffer = (Stack<Frame>) getAttribute(key);
        if (buffer != null) {
            buffer.push(frame);
        }
    }

    @Override
    public Object removeAttribute(Object key) {
        return this.attributes.remove(key);
    }

    protected void removeChannel(int id) {
        channels.remove(id);
    }

    @Override
    public void run() {
        // does it useful to set variables locally ?
        final long idle = group.getIdleTime();
        final NioEventLoopUnsafe unsafe = this.unsafe;
        final AtomicInteger selecting = this.selecting;
        final BlockingQueue<Runnable> events = this.events;
        final DelayedQueue dq = this.delayedQueue;
        long nextIdle = 0;
        long selectTime = idle;
        for (;;) {
            // when this event loop is going to shutdown,we do not handle the last events 
            // because the method "submit" will return false, and if the task is closable,
            // the task will be closed, then free the other things and let it go, the group
            // restart will create a new event loop instead.
            if (!isRunning()) {
                if (!events.isEmpty()) {
                    for (;;) {
                        Runnable event = events.poll();
                        if (event == null) {
                            break;
                        }
                        try {
                            event.run();
                        } catch (Throwable e) {
                            logger.error(e.getMessage(), e);
                        }
                    }
                }
                if (!dq.isEmpty()) {
                    for (;;) {
                        DelayTask t = dq.poll();
                        if (t == null) {
                            break;
                        }
                        if (t.isCanceled()) {
                            continue;
                        }
                        try {
                            t.run();
                        } catch (Throwable e) {
                            printException(logger, e, 1);
                        }
                    }
                }
                closeChannels();
                Util.close(unsafe);
                Util.release(buf);
                return;
            }
            try {
                // the method selector.wakeup is a weight operator, so we use flag "hasTask"
                // and race flag "selecting" to reduce execution times of wake up
                // I am not sure events.size if a better way to instead of hasTask?
                // example method selector.select(...) may throw an io exception 
                // and if we need to try with the method to do something when exception caught?
                int selected;
                if (USE_HAS_TASK) {
                    if (!hasTask && selecting.compareAndSet(0, 1)) {
                        if (events.isEmpty()) {
                            selected = unsafe.select(selectTime);
                        } else {
                            selected = unsafe.selectNow();
                        }
                        selecting.set(0);
                    } else {
                        selected = unsafe.selectNow();
                    }
                    hasTask = false;
                } else {
                    if (events.isEmpty() && selecting.compareAndSet(0, 1)) {
                        if (events.isEmpty()) {
                            selected = unsafe.select(selectTime);
                        } else {
                            selected = unsafe.selectNow();
                        }
                        selecting.set(0);
                    } else {
                        selected = unsafe.selectNow();
                    }
                }
                if (selected > 0) {
                    unsafe.accept(selected);
                }
                long now = System.currentTimeMillis();
                if (now >= nextIdle) {
                    channelIdle(now);
                    nextIdle = now + idle;
                    selectTime = idle;
                } else {
                    selectTime = nextIdle - now;
                }
                if (!events.isEmpty()) {
                    for (;;) {
                        Runnable event = events.poll();
                        if (event == null) {
                            break;
                        }
                        try {
                            event.run();
                        } catch (Throwable e) {
                            logger.error(e.getMessage(), e);
                        }
                    }
                }
                if (!dq.isEmpty()) {
                    for (;;) {
                        DelayTask t = dq.peek();
                        if (t == null) {
                            break;
                        }
                        if (t.isCanceled()) {
                            dq.poll();
                            continue;
                        }
                        long delay = t.getDelay();
                        if (now >= delay) {
                            dq.poll();
                            try {
                                t.done();
                                t.run();
                            } catch (Throwable e) {
                                printException(logger, e, 1);
                            }
                            continue;
                        }
                        if (delay < nextIdle) {
                            selectTime = delay - now;
                        }
                        break;
                    }
                }
            } catch (Throwable e) {
                printException(logger, e, 1);
            }
        }
    }

    public boolean schedule(final DelayTask task) {
        if (inEventLoop()) {
            delayedQueue.offer(task);
            return true;
        } else {
            return submit(new Runnable() {

                @Override
                public void run() {
                    delayedQueue.offer(task);
                }
            });
        }
    }

    @Override
    public void setAttribute(Object key, Object value) {
        this.attributes.put(key, value);
    }

    public boolean submit(Runnable event) {
        if (super.submit(event)) {
            wakeup();
            return true;
        } else {
            if (event instanceof Closeable) {
                Util.close((Closeable) event);
            }
            return false;
        }
    }

    // FIXME 会不会出现这种情况，数据已经接收到本地，但是还没有被EventLoop处理完
    // 执行stop的时候如果确保不会再有数据进来
    @Override
    public void wakeup() {
        if (!inEventLoop() && wakener.compareAndSet(0, 1)) {
            if (USE_HAS_TASK) {
                hasTask = true;
            }
            if (selecting.compareAndSet(0, 1)) {
                selecting.set(0);
            } else {
                unsafe.wakeup();
            }
            wakener.set(0);
        }
    }

    static final class EpollNioEventLoopUnsafe extends NioEventLoopUnsafe {

        final IntMap<ChannelContext> ctxs    = new IntMap<>(256);
        final int                    ep_size = 1024;
        final int                    epfd;
        final int                    eventfd;
        final NioEventLoop           eventLoop;
        final long                   data;
        final long                   ep_events;
        final long                   iovec;

        public EpollNioEventLoopUnsafe(NioEventLoop eventLoop) {
            int iovec_len = eventLoop.group.getWriteBuffers();
            this.eventLoop = eventLoop;
            this.eventfd = Native.new_event_fd();
            this.epfd = Native.epoll_create(ep_size);
            this.ep_events = Native.new_epoll_event_array(ep_size);
            this.data = Unsafe.allocate(256);
            this.iovec = Unsafe.allocate(iovec_len * 16);
            Native.epoll_add(epfd, eventfd, Native.EPOLLIN_ET);
        }

        @Override
        void accept(int size) {
            final int epfd = this.epfd;
            final int eventfd = this.eventfd;
            final NioEventLoop el = this.eventLoop;
            final long ep_events = this.ep_events;
            for (int i = 0; i < size; i++) {
                final int p = i * Native.SIZEOF_EPOLL_EVENT;
                final int e = Unsafe.getInt(ep_events + p);
                final int fd = Unsafe.getInt(ep_events + p + 4);
                if (fd == eventfd) {
                    Native.event_fd_read(fd);
                    continue;
                }
                if (!el.acceptor) {
                    Channel ch = el.getChannel(fd);
                    if (ch != null) {
                        if (ch.isClosed()) {
                            continue;
                        }
                        if ((e & Native.close_event()) != 0) {
                            ch.close();
                            continue;
                        }
                        if (CHANNEL_READ_FIRST) {
                            if ((e & Native.EPOLLIN) != 0) {
                                try {
                                    ch.read();
                                } catch (Throwable ex) {
                                    readExceptionCaught(ch, ex);
                                    continue;
                                }
                            }
                            if ((e & Native.EPOLLOUT) != 0) {
                                int len = ch.write(this);
                                if (len == -1) {
                                    ch.close();
                                    continue;
                                }
                            }
                        } else {
                            if ((e & Native.EPOLLOUT) != 0) {
                                int len = ch.write(this);
                                if (len == -1) {
                                    ch.close();
                                    continue;
                                }
                            }
                            if ((e & Native.EPOLLIN) != 0) {
                                try {
                                    ch.read();
                                } catch (Throwable ex) {
                                    readExceptionCaught(ch, ex);
                                }
                            }
                        }
                    } else {
                        ChannelConnector ctx = (ChannelConnector) ctxs.remove(fd);
                        if ((e & Native.close_event()) != 0 || !Native.finish_connect(fd)) {
                            ctx.channelEstablish(null, NOT_FINISH_CONNECT);
                            continue;
                        }
                        String ra = ((EpollConnectorUnsafe) ctx.getUnsafe()).getRemoteAddr();
                        registChannel(el, ctx, fd, ra, Native.get_port(fd), ctx.getPort(), false);
                    }
                } else {
                    final long cbuf = data;
                    final ChannelAcceptor ctx = (ChannelAcceptor) ctxs.get(fd);
                    final int listenfd = ((EpollAcceptorUnsafe) ctx.getUnsafe()).listenfd;
                    final int cfd = Native.accept(epfd, listenfd, cbuf);
                    if (cfd == -1) {
                        continue;
                    }
                    final NioEventLoopGroup group = ctx.getProcessorGroup();
                    final NioEventLoop targetEL = group.getNext();
                    //10, 0, -7, -30, 0, 0, 0, 0, -2, -128, 0, 0, 0, 0, 0, 0, 80, 1, -107, 55, -55, 36, -124, -125, 2, 0, 0, 0,
                    //10, 0, -4,  47, 0, 0, 0, 0,  0,       0, 0, 0, 0, 0, 0, 0,  0,  0,     -1, -1, -64, -88, -123,     1, 0, 0, 0, 0,
                    int rp = (Unsafe.getByte(cbuf + 2) & 0xff) << 8;
                    rp |= (Unsafe.getByte(cbuf + 3) & 0xff);
                    String ra;
                    if (Unsafe.getShort(cbuf + 18) == -1 && Unsafe.getByte(cbuf + 24) == 0) {
                        //IPv4
                        ra = decodeIPv4(cbuf + 20);
                    } else {
                        //IPv6
                        ra = decodeIPv6(cbuf + 8);
                    }
                    final int _lp = ctx.getPort();
                    final int _rp = rp;
                    final String _ra = ra;
                    targetEL.submit(new Runnable() {

                        @Override
                        public void run() {
                            registChannel(targetEL, ctx, cfd, _ra, _lp, _rp, true);
                        }
                    });
                }
            }
        }

        long getData() {
            return data;
        }

        long getIovec() {
            return iovec;
        }

        @Override
        public void close() throws IOException {
            Unsafe.free(iovec);
            Unsafe.free(data);
            Unsafe.free(ep_events);
            Native.epoll_del(epfd, eventfd);
            Native.close(eventfd);
            Native.close(epfd);
        }

        private void registChannel(NioEventLoop el, ChannelContext ctx, int fd, String ra, int lp,
                int rp, boolean add) {
            IntMap<Channel> channels = el.channels;
            if (channels.size() >= el.chSizeLimit) {
                printException(logger, OVER_CH_SIZE_LIMIT, 2);
                ctx.channelEstablish(null, OVER_CH_SIZE_LIMIT);
                return;
            }
            int epfd = ((EpollNioEventLoopUnsafe) el.unsafe).epfd;
            int res;
            if (add) {
                res = Native.epoll_add(epfd, fd, Native.all_event());
            } else {
                res = Native.epoll_mod(epfd, fd, Native.all_event());
            }
            if (res == -1) {
                if (add) {
                    Native.close(fd);
                }else{
                    ctx.channelEstablish(null, new IOException(Native.errstr()));
                }
                return;
            }
            Channel ch = new Channel(el, ctx, new EpollChannelUnsafe(epfd, fd, ra, lp, rp));
            channels.put(fd, ch);
            ctx.getChannelManager().putChannel(ch);
            if (ch.isEnableSsl()) {
                // fire open event later
                if (ctx.getSslContext().isClient()) {
                    ch.writeAndFlush(ByteBuf.empty());
                }
            } else {
                // fire open event immediately when plain ch
                ch.fireOpened();
                ctx.channelEstablish(ch, null);
            }
        }

        @Override
        int select(long timeout) throws IOException {
            return Native.epoll_wait(epfd, ep_events, ep_size, timeout);
        }

        @Override
        int selectNow() throws IOException {
            return Native.epoll_wait(epfd, ep_events, ep_size, 0);
        }

        @Override
        void wakeup() {
            Native.event_fd_write(eventfd, 1L);
        }

    }

    static final class JavaNioEventLoopUnsafe extends NioEventLoopUnsafe {

        private static final boolean  ENABLE_SELKEY_SET = checkEnableSelectionKeySet();
        private final NioEventLoop    eventLoop;
        private final SelectionKeySet selectionKeySet;
        private final Selector        selector;
        private final ByteBuffer[]    writeBuffers;

        JavaNioEventLoopUnsafe(NioEventLoop eventLoop) throws IOException {
            if (ENABLE_SELKEY_SET) {
                this.selectionKeySet = new SelectionKeySet(1024);
            } else {
                this.selectionKeySet = null;
            }
            this.eventLoop = eventLoop;
            this.selector = openSelector(selectionKeySet);
            this.writeBuffers = new ByteBuffer[eventLoop.group.getWriteBuffers()];
        }

        ByteBuffer[] getWriteBuffers() {
            return writeBuffers;
        }

        @Override
        void accept(int size) {
            if (ENABLE_SELKEY_SET) {
                final SelectionKeySet keySet = selectionKeySet;
                for (int i = 0; i < keySet.size; i++) {
                    SelectionKey k = keySet.keys[i];
                    keySet.keys[i] = null;
                    accept(k);
                }
                keySet.reset();
            } else {
                Set<SelectionKey> sks = selector.selectedKeys();
                for (SelectionKey k : sks) {
                    accept(k);
                }
                sks.clear();
            }
        }

        private void accept(final SelectionKey key) {
            if (!key.isValid()) {
                key.cancel();
                return;
            }
            final Object attach = key.attachment();
            if (attach instanceof Channel) {
                final Channel ch = (Channel) attach;
                final int readyOps = key.readyOps();
                if (CHANNEL_READ_FIRST) {
                    if ((readyOps & SelectionKey.OP_READ) != 0) {
                        try {
                            ch.read();
                        } catch (Throwable e) {
                            readExceptionCaught(ch, e);
                        }
                    } else if ((readyOps & SelectionKey.OP_WRITE) != 0) {
                        int len = ch.write(this);
                        if (len == -1) {
                            ch.close();
                            return;
                        }
                    }
                } else {
                    if ((readyOps & SelectionKey.OP_WRITE) != 0) {
                        int len = ch.write(this);
                        if (len == -1) {
                            ch.close();
                            return;
                        }
                    }
                    if ((readyOps & SelectionKey.OP_READ) != 0) {
                        try {
                            ch.read();
                        } catch (Throwable e) {
                            readExceptionCaught(ch, e);
                        }
                    }
                }
            } else {
                if (attach instanceof ChannelAcceptor) {
                    final ChannelAcceptor acceptor = (ChannelAcceptor) attach;
                    final JavaAcceptorUnsafe au = (JavaAcceptorUnsafe) acceptor.getUnsafe();
                    ServerSocketChannel channel = au.getSelectableChannel();
                    try {
                        //有时候还未regist selector，但是却能selector到sk
                        //如果getLocalAddress为空则不处理该sk
                        if (channel.getLocalAddress() == null) {
                            return;
                        }
                        final SocketChannel ch = channel.accept();
                        if (ch == null) {
                            return;
                        }
                        final NioEventLoopGroup group = acceptor.getProcessorGroup();
                        final NioEventLoop targetEL = group.getNext();
                        ch.configureBlocking(false);
                        targetEL.submit(new Runnable() {

                            @Override
                            public void run() {
                                try {
                                    registChannel(ch, targetEL, acceptor, true);
                                } catch (IOException e) {
                                    printException(logger, e, 1);
                                }
                            }
                        });
                    } catch (Throwable e) {
                        printException(logger, e, 1);
                    }
                } else {
                    final ChannelConnector connector = (ChannelConnector) attach;
                    final SocketChannel channel = getSocketChannel(connector);
                    try {
                        if (channel.finishConnect()) {
                            int ops = key.interestOps();
                            ops &= ~SelectionKey.OP_CONNECT;
                            key.interestOps(ops);
                            registChannel(channel, eventLoop, connector, false);
                        } else {
                            connector.channelEstablish(null, NOT_FINISH_CONNECT);
                        }
                    } catch (Throwable e) {
                        connector.channelEstablish(null, e);
                    }
                }
            }
        }

        @Override
        public void close() {
            Util.close(selector);
        }

        protected Selector getSelector() {
            return selector;
        }

        private SocketChannel getSocketChannel(ChannelConnector connector) {
            JavaConnectorUnsafe cu = (JavaConnectorUnsafe) connector.getUnsafe();
            return cu.getSelectableChannel();
        }

        private void registChannel(SocketChannel jch, NioEventLoop el, ChannelContext ctx,
                boolean acceptor) throws IOException {
            IntMap<Channel> channels = el.channels;
            if (channels.size() >= el.chSizeLimit) {
                printException(logger, OVER_CH_SIZE_LIMIT, 2);
                ctx.channelEstablish(null, OVER_CH_SIZE_LIMIT);
                return;
            }
            JavaNioEventLoopUnsafe elUnsafe = (JavaNioEventLoopUnsafe) el.unsafe;
            NioEventLoopGroup g = el.getGroup();
            int channelId = g.getChannelIds().getAndIncrement();
            SelectionKey sk = jch.register(elUnsafe.selector, SelectionKey.OP_READ);
            Util.close(channels.get(channelId));
            Util.close((Channel) sk.attachment());
            String ra;
            int lp;
            int rp;
            if (acceptor) {
                InetSocketAddress address = (InetSocketAddress) jch.getRemoteAddress();
                lp = ctx.getPort();
                ra = address.getAddress().getHostAddress();
                rp = address.getPort();
            } else {
                InetSocketAddress remote = (InetSocketAddress) jch.getRemoteAddress();
                InetSocketAddress local = (InetSocketAddress) jch.getLocalAddress();
                lp = local.getPort();
                ra = remote.getAddress().getHostAddress();
                rp = remote.getPort();
            }
            JavaChannelUnsafe unsafe = new JavaChannelUnsafe(sk, ra, lp, rp, channelId);
            sk.attach(new Channel(el, ctx, unsafe));
            Channel ch = (Channel) sk.attachment();
            channels.put(channelId, ch);
            ctx.getChannelManager().putChannel(ch);
            if (ch.isEnableSsl()) {
                // fire open event later
                if (ctx.getSslContext().isClient()) {
                    ch.writeAndFlush(ByteBuf.empty());
                }
            } else {
                // fire open event immediately when plain ch
                ch.fireOpened();
                ctx.channelEstablish(ch, null);
            }
        }

        @Override
        int select(long timeout) throws IOException {
            return selector.select(timeout);
        }

        @Override
        int selectNow() throws IOException {
            return selector.selectNow();
        }

        @Override
        void wakeup() {
            selector.wakeup();
        }

        private static boolean checkEnableSelectionKeySet() {
            Selector selector = null;
            try {
                selector = openSelector(new SelectionKeySet(0));
                return selector.selectedKeys().getClass() == SelectionKeySet.class;
            } catch (Throwable e) {
                return false;
            } finally {
                Util.close(selector);
            }
        }

        @SuppressWarnings("rawtypes")
        private static Selector openSelector(final SelectionKeySet keySet) throws IOException {
            final SelectorProvider provider = SelectorProvider.provider();
            final Selector selector = provider.openSelector();
            Object res = AccessController.doPrivileged(new PrivilegedAction<Object>() {
                @Override
                public Object run() {
                    try {
                        return Class.forName("sun.nio.ch.SelectorImpl");
                    } catch (Throwable cause) {
                        return cause;
                    }
                }
            });
            if (res instanceof Throwable) {
                return selector;
            }
            final Class selectorImplClass = (Class) res;
            res = AccessController.doPrivileged(new PrivilegedAction<Object>() {
                @Override
                public Object run() {
                    try {
                        Field selectedKeysField = selectorImplClass
                                .getDeclaredField("selectedKeys");
                        Field publicSelectedKeysField = selectorImplClass
                                .getDeclaredField("publicSelectedKeys");
                        Throwable cause = Util.trySetAccessible(selectedKeysField);
                        if (cause != null) {
                            return cause;
                        }
                        cause = Util.trySetAccessible(publicSelectedKeysField);
                        if (cause != null) {
                            return cause;
                        }
                        selectedKeysField.set(selector, keySet);
                        publicSelectedKeysField.set(selector, keySet);
                        return null;
                    } catch (Exception e) {
                        return e;
                    }
                }
            });
            if (res instanceof Throwable) {
                return selector;
            }
            return selector;
        }

    }

    static abstract class NioEventLoopUnsafe implements Closeable {

        abstract void accept(int size);

        abstract int select(long timeout) throws IOException;

        abstract int selectNow() throws IOException;

        abstract void wakeup();

    }

    static class SelectionKeySet extends AbstractSet<SelectionKey> {

        SelectionKey[] keys;
        int            size;

        SelectionKeySet(int cap) {
            keys = new SelectionKey[cap];
        }

        @Override
        public boolean add(SelectionKey o) {
            keys[size++] = o;
            if (size == keys.length) {
                increaseCapacity();
            }
            return true;
        }

        @Override
        public boolean contains(Object o) {
            return false;
        }

        private void increaseCapacity() {
            keys = Arrays.copyOf(keys, size << 1);
        }

        @Override
        public Iterator<SelectionKey> iterator() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean remove(Object o) {
            return false;
        }

        void reset() {
            size = 0;
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public String toString() {
            return "SelectionKeySet[" + size() + "]";
        }
    }

    private static String decodeIPv4(long addr) {
        StringBuilder s = FastThreadLocal.get().getStringBuilder();
        s.append(ByteUtil.getNumString(Unsafe.getByte(addr + 0)));
        s.append('.');
        s.append(ByteUtil.getNumString(Unsafe.getByte(addr + 1)));
        s.append('.');
        s.append(ByteUtil.getNumString(Unsafe.getByte(addr + 2)));
        s.append('.');
        s.append(ByteUtil.getNumString(Unsafe.getByte(addr + 3)));
        return s.toString();
    }

    private static String decodeIPv6(long addr) {
        StringBuilder s = FastThreadLocal.get().getStringBuilder();
        for (int i = 0; i < 8; i++) {
            byte b1 = Unsafe.getByte(addr + (i << 1));
            byte b2 = Unsafe.getByte(addr + (i << 1) + 1);
            if (b1 == 0 && b2 == 0) {
                s.append('0');
                s.append(':');
            } else {
                s.append(ByteUtil.getHexString(b1));
                s.append(ByteUtil.getHexString(b2));
                s.append(':');
            }
        }
        s.setLength(s.length() - 1);
        return s.toString();
    }

    private static Logger newLogger() {
        return LoggerFactory.getLogger(NioEventLoop.class);
    }

    private static IOException NOT_FINISH_CONNECT() {
        return Util.unknownStackTrace(new IOException("not finish connect"), SocketChannel.class,
                "finishConnect(...)");
    }

    private static IOException OVER_CH_SIZE_LIMIT() {
        return Util.unknownStackTrace(new IOException("over channel size limit"),
                NioEventLoop.class, "registChannel(...)");
    }

    private static void readExceptionCaught(Channel ch, Throwable ex) {
        ch.close();
        Develop.printException(logger, ex, 2);
        if (!ch.isSslHandshakeFinished()) {
            ch.getContext().channelEstablish(ch, ex);
        }
    }

}