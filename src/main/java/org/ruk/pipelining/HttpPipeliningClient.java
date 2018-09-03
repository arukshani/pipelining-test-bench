package org.ruk.pipelining;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
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
                            ch.pipeline().addLast(new HttpClientCodec());
                            ch.pipeline().addLast(new HttpObjectAggregator(1024 * 512));
                            ch.pipeline().addLast(handler);
                        }
                    });
            b.option(ChannelOption.SO_KEEPALIVE, true).option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 150000);
            ChannelFuture f = b.connect().sync();
            channel = f.channel();
            PipeliningRunner.addConnection();
           /* f.addListener((ChannelFutureListener) future -> {
                if (future.isSuccess() && future.isDone()) {

                    //   future.channel().write(httpRequest);


                } else {
                    log.error("Error occurred while trying to connect to redirect channel.", f.cause());
                }
            });*/
            f.channel().closeFuture().sync(); //Blocks until the channel closes
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
