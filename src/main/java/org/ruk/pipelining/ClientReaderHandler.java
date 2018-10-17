package org.ruk.pipelining;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

@Sharable
public class ClientReaderHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(ClientReaderHandler.class);

    private final int clientId;
    private final EventLoopGroup eventLoopGroup;
    private final CountDownLatch countDownLatch;
    private final AtomicInteger noOfMessagesReceived;
    private final AtomicInteger noOfErrorMessagesAtomicInteger;
    private long endTime;
    private int expectedNoOfMessages;
    private ChannelHandlerContext ctx;
    private final Queue<String> messageQueue;


    public ClientReaderHandler(int clientId, int expectedNoOfMessages, Queue<String> messageQueue,
                               EventLoopGroup eventLoopGroup, CountDownLatch countDownLatch) {
        this.clientId = clientId;
        this.expectedNoOfMessages = expectedNoOfMessages;
        this.eventLoopGroup = eventLoopGroup;
        this.countDownLatch = countDownLatch;
        this.noOfMessagesReceived = new AtomicInteger();
        this.noOfErrorMessagesAtomicInteger = new AtomicInteger();
        this.messageQueue = messageQueue;
    }


    @Override
    public void channelActive(ChannelHandlerContext ctx) throws InterruptedException {
        this.ctx = ctx;
        System.out.println("Channel active from client side: Client " + clientId);

        //   log.info("Client {}: Sending messages...", clientId);

        int messageId = 0;
        while (true) {
            sendPipeliningRequest("TestMsg" + Integer.toString(messageId));
            if (messageId == expectedNoOfMessages - 1) {
               // setStopReceivingMessages();
                break;
            }
            messageId++;
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        //  System.out.println("server response received from client: " + clientId);
        String expected = messageQueue.remove();
        if (msg instanceof FullHttpResponse) {
            String actual = getEntityBodyFrom((FullHttpResponse) msg);
            if (!expected.equals(actual)) {
                noOfErrorMessagesAtomicInteger.incrementAndGet();
                log.error(String.format("Error receiving message expected: %s, actual: %s", expected, actual));
            } else {
                log.info("Client:" + clientId + " received from server : " + actual + " Expected: " + expected);
            }

            if (expectedNoOfMessages == noOfMessagesReceived.incrementAndGet()) {
                log.info("Channel about to be closed:" + clientId);
                ctx.close();
            }
        } else {
            System.out.println("Not a full http response");
        }
        ReferenceCountUtil.release(msg);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx,
                                Throwable cause) {
       // cause.printStackTrace();
        log.error("Exception occurred closing the connection", cause.getMessage());
        ctx.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        endTime = System.currentTimeMillis();
        logMessage("Pipelining Client disconnected!");
        eventLoopGroup.shutdownGracefully().addListener(future -> {
            PipeliningRunner.removeConnection();
            countDownLatch.countDown();
        });
    }

    public long getEndTime() {
        return endTime;
    }

    private void logMessage(String msg) {
        log.info("Client {}: {}", clientId, msg);
    }

    public int getNoOfErrorMessages() {
        return noOfErrorMessagesAtomicInteger.get();
    }

    public int getNoOfMessagesReceived() {
        return noOfMessagesReceived.get();
    }

    public void setStopReceivingMessages() {
       /* if (expectedNoOfMessages == -1) {
            ctx.writeAndFlush(ChannelFutureListener.CLOSE);
        }*/
    }

    public static String getEntityBodyFrom(FullHttpResponse httpResponse) {
        return httpResponse.content().toString(CharsetUtil.UTF_8);
    }

    public void sendPipeliningRequest(String messageId) throws InterruptedException {
        log.info("Send piplining request : " + messageId + " Client : " + clientId);
        HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/test/outOfOrder");
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        request.headers().set("message-id", messageId);
        messageQueue.add(messageId);
        ctx.writeAndFlush(request);
    }
}
