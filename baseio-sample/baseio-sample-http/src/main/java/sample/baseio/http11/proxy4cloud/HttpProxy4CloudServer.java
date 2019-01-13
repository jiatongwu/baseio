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
package sample.baseio.http11.proxy4cloud;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

import com.firenio.baseio.buffer.ByteBuf;
import com.firenio.baseio.codec.http11.ClientHttpCodec;
import com.firenio.baseio.codec.http11.ClientHttpFrame;
import com.firenio.baseio.codec.http11.HttpCodec;
import com.firenio.baseio.codec.http11.HttpFrame;
import com.firenio.baseio.codec.http11.HttpHeader;
import com.firenio.baseio.codec.http11.HttpMethod;
import com.firenio.baseio.codec.http11.HttpStatus;
import com.firenio.baseio.collection.IntMap;
import com.firenio.baseio.common.Util;
import com.firenio.baseio.component.Channel;
import com.firenio.baseio.component.ChannelAcceptor;
import com.firenio.baseio.component.ChannelConnector;
import com.firenio.baseio.component.Frame;
import com.firenio.baseio.component.IoEventHandle;
import com.firenio.baseio.component.LoggerChannelOpenListener;
import com.firenio.baseio.component.NioEventLoop;
import com.firenio.baseio.component.NioEventLoopGroup;
import com.firenio.baseio.component.ProtocolCodec;
import com.firenio.baseio.log.DebugUtil;

public class HttpProxy4CloudServer {

    static final String                CONNECT_RES     = "HTTP/1.1 200 Connection Established\r\n\r\n";
    static final ByteBuf               CONNECT_RES_BUF = ByteBuf.wrap(CONNECT_RES.getBytes());
    static final String                netHost;
    static final int                   netPort         = 18088;
    static final HttpProxy4CloudServer server          = new HttpProxy4CloudServer();
    static {
        String host = null;
        try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", netPort), 50);
            Util.close(socket);
            host = "127.0.0.1";
        } catch (IOException e) {
            host = "47.52.62.51";
        }
        netHost = host;
        DebugUtil.debug("remote host: " + netHost);
    }
    private ChannelAcceptor  context;

    private volatile boolean enable = true;

    public void disable() {
        enable = false;
    }

    public void enable() {
        enable = true;
    }

    public synchronized void stop() {
        Util.unbind(context);
    }

    public synchronized void strtup(NioEventLoopGroup serverG, int port) throws Exception {
        if (context != null && context.isActive()) {
            return;
        }

        IoEventHandle eventHandle = new IoEventHandle() {

            @Override
            public void accept(Channel ch_src, Frame frame) throws Exception {
                final HttpFrame f = (HttpFrame) frame;
                if (!enable) {
                    f.setStatus(HttpStatus.C503);
                    f.write("503 service unavailable".getBytes());
                    ch_src.writeAndFlush(f);
                    return;
                }
                if (f.getMethod() == HttpMethod.CONNECT) {
                    ch_src.writeAndFlush(CONNECT_RES_BUF.duplicate());
                    ProxySession4Cloud s = ProxySession4Cloud.get(ch_src);
                    String[] arr = f.getHost().split(":");
                    s.mask = (byte) arr[0].getBytes().length;
                    s.host = arr[0];
                    if (arr.length == 2) {
                        s.port = Integer.parseInt(arr[1]);
                    }
                    s.handshakeFinished = true;
                } else {
                    String host = f.getHost();
                    String[] arr = host.split(":");
                    final int port;
                    if (arr.length == 2) {
                        port = Integer.parseInt(arr[1]);
                    } else {
                        port = 80;
                    }
                    if (f.getRequestHeaders().remove(HttpHeader.Proxy_Connection.getId()) == null) {
                        return;
                    }
                    NioEventLoop el = ch_src.getEventLoop();
                    ChannelConnector context = new ChannelConnector(el, netHost, netPort);
                    context.addProtocolCodec(new ClientHttpCodec() {

                        public Frame decode(Channel ch, ByteBuf src) throws Exception {
                            ProxySession4Cloud s = ProxySession4Cloud.get(ch);
                            NetDataTransferServer.mask(src, s.mask);
                            return super.decode(ch, src);
                        };

                    });

                    context.setIoEventHandle(new IoEventHandle() {

                        @Override
                        public void accept(Channel ch, Frame frame) throws Exception {
                            ClientHttpFrame res = (ClientHttpFrame) frame;
                            IntMap<String> hs = res.getResponse_headers();
                            for(hs.scan();hs.hasNext();){
                                String v = hs.nextValue();
                                if (v == null) {
                                    continue;
                                }
                                f.setResponseHeader(hs.key(), v.getBytes());
                            }
                            f.removeResponseHeader(HttpHeader.Content_Length);
                            if (res.getContent() != null) {
                                f.setContent(res.getContent());
                            } else if (res.isChunked()) {
                                f.removeResponseHeader(HttpHeader.Transfer_Encoding);
                                f.removeResponseHeader(HttpHeader.Content_Encoding);
                                f.setBytes("not support chunked now.".getBytes());

                            }
                            ch_src.writeAndFlush(f);
                            ch.close();
                        }
                    });
                    context.setPrintConfig(false);
                    context.addChannelEventListener(new LoggerChannelOpenListener());
                    context.connect((ch, ex) -> {
                        if (ex == null) {
                            ProxySession4Cloud s = ProxySession4Cloud.get(ch);
                            HttpFrame req = new ClientHttpFrame(f.getRequestURL(), f.getMethod());
                            req.setRequestParams(f.getRequestParams());
                            req.setRequestHeaders(f.getRequestHeaders());
                            ByteBuf body = null;
                            ByteBuf head = null;
                            try {
                                byte[] realHost = arr[0].getBytes();
                                body = ch.encode(req);
                                int len = 5 + realHost.length;
                                s.mask = (byte) realHost.length;
                                head = ch.alloc().allocate(len);
                                head.putByte((byte) realHost.length);
                                head.putByte((byte) 83);
                                head.putByte((byte) 38);
                                head.putUnsignedShort(port);
                                head.put(realHost);
                                NetDataTransferServer.mask(body, s.mask);
                                ch.writeAndFlush(head.flip());
                                ch.writeAndFlush(body);
                            } catch (Exception e) {
                                Util.release(head);
                                Util.release(body);
                            }
                        }
                    });
                }
            }
        };

        context = new ChannelAcceptor(serverG, 8088);
        context.addProtocolCodec(new HttpProxy4CloudCodec());
        context.setIoEventHandle(eventHandle);
        context.addChannelEventListener(new LoggerChannelOpenListener());
        context.bind();
    }

    class HttpProxy4CloudCodec extends HttpCodec {

        @Override
        public Frame decode(final Channel ch_src, ByteBuf src) throws Exception {
            ProxySession4Cloud s = ProxySession4Cloud.get(ch_src);
            if (s.handshakeFinished) {
                if (s.connector == null || !s.connector.isConnected()) {
                    NioEventLoop el = ch_src.getEventLoop();
                    ChannelConnector context = new ChannelConnector(el, netHost, netPort);
                    context.addProtocolCodec(new ProtocolCodec() {

                        @Override
                        public Frame decode(Channel ch, ByteBuf src) throws IOException {
                            ByteBuf buf = ch_src.alloc().allocate(src.remaining());
                            buf.put(src);
                            buf.flip();
                            NetDataTransferServer.mask(buf, s.mask);
                            ch_src.writeAndFlush(buf);
                            return null;
                        }

                        @Override
                        public ByteBuf encode(Channel ch, Frame frame) throws IOException {
                            return null;
                        }

                        @Override
                        public String getProtocolId() {
                            return "http-proxy-connect";
                        }

                        @Override
                        public int headerLength() {
                            return 0;
                        }
                    });
                    context.setPrintConfig(false);
                    context.addChannelEventListener(new LoggerChannelOpenListener());
                    ByteBuf buf = ch_src.alloc().allocate(src.remaining());
                    buf.put(src);
                    s.connector = context;
                    s.connector.connect((ch_target, ex) -> {
                        if (ex == null) {
                            byte[] realHost = s.host.getBytes();
                            int len = 5 + realHost.length;
                            ByteBuf head = ch_target.alloc().allocate(len);
                            head.putByte((byte) realHost.length);
                            head.putByte((byte) 83);
                            head.putByte((byte) 38);
                            head.putUnsignedShort(s.port);
                            head.put(realHost);
                            ch_target.writeAndFlush(head.flip());
                            buf.flip();
                            NetDataTransferServer.mask(buf, (byte) realHost.length);
                            ch_target.writeAndFlush(buf);
                        } else {
                            buf.release();
                            ProxySession4Cloud.remove(ch_src);
                            Util.close(ch_src);
                        }
                    });
                } else {
                    ByteBuf buf = ch_src.alloc().allocate(src.remaining());
                    buf.put(src);
                    buf.flip();
                    NetDataTransferServer.mask(buf, s.mask);
                    s.connector.getChannel().writeAndFlush(buf);
                }
                return null;
            }
            return super.decode(ch_src, src);
        }

        @Override
        protected void parse_line_one(HttpFrame f, CharSequence line) {
            if (line.charAt(0) == 'C' && line.charAt(1) == 'O' && line.charAt(2) == 'N'
                    && line.charAt(3) == 'N' && line.charAt(4) == 'E' && line.charAt(5) == 'C'
                    && line.charAt(6) == 'T' && line.charAt(7) == ' ') {
                f.setMethod(HttpMethod.CONNECT);
                parseRequestURL(f, 8, line);
            } else {
                super.parse_line_one(f, line);
            }
        }

    }

    public static class ProxySession4Cloud {

        static final String     ProxySessionChAttr = "_ProxySessionChAttr";
        public ChannelConnector connector;
        public boolean          handshakeFinished;
        public String           host;
        public byte             mask;
        public int              port               = 80;

        @Override
        public String toString() {
            return host + ":" + port;
        }

        public static ProxySession4Cloud get(Channel ch) {
            ProxySession4Cloud s = (ProxySession4Cloud) ch.getAttribute(ProxySessionChAttr);
            if (s == null) {
                s = new ProxySession4Cloud();
                ch.setAttribute(ProxySessionChAttr, s);
            }
            return s;
        }

        public static void remove(Channel ch) {
            ProxySession4Cloud s = (ProxySession4Cloud) ch.removeAttribute(ProxySessionChAttr);
            if (s != null) {
                s.connector = null;
            }
        }
    }

    public static HttpProxy4CloudServer get() {
        return server;
    }

    public static void main(String[] args) throws Exception {

        get().strtup(new NioEventLoopGroup(true), 8088);

    }

}