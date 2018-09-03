package org.ruk.pipelining;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestEncoder;
import io.netty.handler.codec.http.HttpResponseDecoder;
import io.netty.handler.codec.http.HttpVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import javax.net.ssl.SSLException;

public class HttpPipeliningClient {

    private static final Logger log = LoggerFactory.getLogger(HttpPipeliningClient.class);

    private final int clientId;
    private final int expectedNoOfMessages;
    private final String url;
    private final Queue<String> messageQueue;
    private final CountDownLatch countDownLatch;
    private Channel channel;
    private ClientReaderHandler handler;

    public HttpPipeliningClient(int clientId, int expectedNoOfMessages, String url, CountDownLatch countDownLatch) {
        this.clientId = clientId;
        this.expectedNoOfMessages = expectedNoOfMessages;
        this.url = url;
        this.countDownLatch = countDownLatch;
        this.messageQueue = new ConcurrentLinkedQueue<>();
    }

    public void init() throws URISyntaxException, SSLException, InterruptedException {
       /* URI uri = new URI(url);
        String scheme = uri.getScheme() == null ? "http" : uri.getScheme();
        final String host = uri.getHost() == null ? "127.0.0.1" : uri.getHost();
        final int port;
        if (uri.getPort() == -1) {
            if ("http".equalsIgnoreCase(scheme)) {
                port = 80;
            } else if ("https".equalsIgnoreCase(scheme)) {
                port = 443;
            } else {
                port = -1;
            }
        } else {
            port = uri.getPort();
        }*/

        EventLoopGroup group = new NioEventLoopGroup();
        ClientReaderHandler handler = new ClientReaderHandler(clientId, expectedNoOfMessages, messageQueue,
                group, countDownLatch);
        this.handler = handler;
        try {
            Bootstrap b = new Bootstrap();
            b.group(group)
                    .channel(NioSocketChannel.class)
                    .remoteAddress(new InetSocketAddress("localhost", 9090))
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(SocketChannel ch)
                                throws Exception {
                           // ch.pipeline().addLast("decoder", new HttpResponseDecoder());
                            ch.pipeline().addLast(new HttpClientCodec());
                            ch.pipeline().addLast(new HttpObjectAggregator(1024 * 512));
                           // ch.pipeline().addLast("encoder", new HttpRequestEncoder());
                            ch.pipeline().addLast(handler);
                        }
                    });
            ChannelFuture f = b.connect().sync();
            channel = f.channel();
            PipeliningRunner.addConnection();
            f.channel().closeFuture().sync();
        } finally {
            group.shutdownGracefully().sync();
        }
    }

    public long getEndTime() {
        return handler.getEndTime();
    }

    public int getNoOfErrorMessages() {
        return handler.getNoOfErrorMessages();
    }

    public int getNoOfMessagesReceived() {
        return handler.getNoOfMessagesReceived();
    }

    public void stop() {
        handler.setStopReceivingMessages();
    }
}
