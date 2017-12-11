package org.smileframework.web.server.dispatch;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.multipart.*;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.smileframework.tool.logmanage.LoggerManager;
import org.smileframework.web.annotation.RequestMethod;
import org.smileframework.web.handler.WebDefinition;
import org.smileframework.web.server.modle.MessageRequest;
import org.smileframework.web.server.modle.MessageResponse;
import org.smileframework.web.server.strategy.DefaultTaskProcessChoice;
import org.smileframework.web.server.strategy.SmileTaskChoice;
import org.smileframework.web.threadpool.SmileMessageExecutor;
import org.smileframework.web.util.NettyResponse;
import org.smileframework.web.util.WebContextTools;
import org.smileframework.web.util.WebTools;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static io.netty.buffer.Unpooled.copiedBuffer;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;

/**
 * @Package: com.example.netty
 * @Description: 负责品类分发，中心处理器
 * @author: liuxin
 * @date: 17/4/11 下午3:51
 */
public class HttpDispatchServerHandler extends SimpleChannelInboundHandler<HttpObject> {

    private static final Logger logger = LoggerManager.getLogger(HttpDispatchServerHandler.class);

    private HttpRequest request;

    private static final HttpDataFactory factory = new DefaultHttpDataFactory(DefaultHttpDataFactory.MINSIZE); //Disk

    private HttpPostRequestDecoder decoder;


    /**
     * 1）客户端连接服务端
     2）在客户端的的ChannelPipeline中加入一个比较特殊的IdleStateHandler，设置一下客户端的写空闲时间，例如5s
     3）当客户端的所有ChannelHandler中4s内没有write事件，则会触发userEventTriggered方法（上文介绍过）
     * @param ctx
     * @param evt
     * @throws Exception
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        super.userEventTriggered(ctx, evt);
    }

    /**
     * 当连接通道关闭后清理
     *
     * @param ctx
     * @throws Exception
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (decoder != null) {
            decoder.cleanFiles();
        }
    }

    /**
     * msg的类型
     * DefaultHttpRequest
     */
    public void messageReceivedDispatch(ChannelHandlerContext ctx, HttpObject msg) throws Exception {
        String dispatchUrl = "";
        Map<String, Object> headerMaps = new ConcurrentHashMap<>();
        if (msg instanceof HttpRequest) {
            HttpRequest req = this.request = (HttpRequest) msg;
            HttpHeaders headers = req.headers();
            headers.entries().stream().forEach(x -> {
                headerMaps.put(x.getKey(), x.getValue());
            });
            String methodName = request.getMethod().name();
            dispatchUrl = req.getUri();
            String randomUUID = UUID.randomUUID().toString().replaceAll("-", "");
            Map<String, String> requestParams = new ConcurrentHashMap<>();
            // 处理get请求
            if (methodName.equalsIgnoreCase("GET")) {
                QueryStringDecoder decoder = new QueryStringDecoder(dispatchUrl);
                Map<String, List<String>> parame = decoder.parameters();
                Iterator<Map.Entry<String, List<String>>> iterator = parame.entrySet().iterator();
                while (iterator.hasNext()) {
                    Map.Entry<String, List<String>> next = iterator.next();
                    requestParams.put(next.getKey(), next.getValue().get(0));
                }
            }
            // 处理POST请求
            if (methodName.equalsIgnoreCase("POST")) {
                HttpPostRequestDecoder decoder = new HttpPostRequestDecoder(
                        new DefaultHttpDataFactory(false), req);
                List<InterfaceHttpData> postData = decoder.getBodyHttpDatas(); //
                for (InterfaceHttpData data : postData) {
                    if (data.getHttpDataType() == InterfaceHttpData.HttpDataType.Attribute) {
                        MemoryAttribute attribute = (MemoryAttribute) data;
                        requestParams.put(attribute.getName(), attribute.getValue());
                    }
                }
            }

            RequestMethod requestMethod = WebTools.getRequestMethod(methodName);
            WebDefinition webDefinition = WebContextTools.getWebDefinitionByUrl(dispatchUrl, requestMethod);
            if (webDefinition instanceof Web404Definition){
                NettyResponse.writeResponse(ctx.channel(),"Not Found",HttpResponseStatus.NOT_FOUND);
                return;
            }
            if (webDefinition instanceof Web405Definition){
                NettyResponse.writeResponse(ctx.channel(),"Method Not Allowed",HttpResponseStatus.METHOD_NOT_ALLOWED);
                return;
            }
            /**
             * //TODO 异步处理url获取处理的 bean
             */
            MessageRequest messageRequest = new MessageRequest(randomUUID, requestMethod, requestParams, webDefinition, headerMaps);
            MessageResponse messageResponse = new MessageResponse();
            /**
             * //TODO 根据启动配置,当如果是rpc服务就要使用MessageProcessTask
             * 如果是本地服务使用LocalMessageTask
             *
             * 此时MessageRequest和MessageResponse都是final 修饰,目的是保证始终是对当前的MessageResponse
             */
            SmileTaskChoice smileTaskChoice = new DefaultTaskProcessChoice(messageRequest, messageResponse, false);
            /**
             * //TODO 交给线程处理异步处理响应
             */
            SmileMessageExecutor.submit(smileTaskChoice.choice(), ctx, req, messageRequest, messageResponse);
        }
    }


    /**
     * 当未发现路径，返回提示
     *
     * @param ctx
     */
    private void writeMenu(ChannelHandlerContext ctx) {
        String strVar = "{code:2000,message:\"check the url\"}";
        ByteBuf buf = copiedBuffer(strVar, CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK, buf);
        response.headers().set(CONTENT_TYPE, "text/html; charset=UTF-8");
        response.headers().set(CONTENT_LENGTH, buf.readableBytes());
        ctx.channel().writeAndFlush(response);

    }

    /**
     * exceptionCaught() 事件处理方法是当出现 Throwable 对象才会被调用，
     * 即当 Netty 由于 IO 错误或者处理器在处理事件时抛出的异常时。
     * 在大部分情况下，捕获的异常应该被记录下来并且把关联的 channel 给关闭掉。
     * 然而这个方法的处理方式会在遇到不同异常的情况下有不同的实现，
     * 比如你可能想在关闭连接之前发送一个错误码的响应消息。
     *
     * @param ctx
     * @param cause
     * @throws Exception
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        logger.info("当前TheadName:{},ChannelName:{},处理异常关闭:{}", Thread.currentThread().getName(), ctx.channel());
        ctx.channel().close();
        Channel channel = ctx.channel();
        if (!channel.isActive()) {
            logger.info("*******请求客户端断开了连接******");
        }
        throw new RuntimeException(cause);
    }

    /**
     * 读取的时候调用逻辑处理方法
     * 覆盖了 channelRead0() 事件处理方法。
     * 每当从服务端读到客户端写入信息时，
     * 其中如果你使用的是 Netty 5.x 版本时，
     * 需要把 channelRead0() 重命名为messageReceived()
     *
     * @param ctx
     * @param msg
     * @throws Exception
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) throws Exception {
        logger.debug("访问本地址:{},访问机构IP:{}", ctx.channel().localAddress(), ctx.channel().remoteAddress());
        messageReceivedDispatch(ctx, msg);
    }


}
