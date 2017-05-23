package me.j360.rpc.client;

import com.google.protobuf.GeneratedMessageV3;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;
import me.j360.rpc.codec.protobuf.RPCHeader;
import me.j360.rpc.codec.protobuf.RPCMessage;
import me.j360.rpc.codec.protostuff.RpcRequest;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Package: me.j360.rpc.client
 * User: min_xu
 * Date: 2017/5/17 下午4:35
 * 说明：
 */

@Slf4j
public class DefaultFuture<T> implements ResponseFuture {

    private static final Map<String, Channel> CHANNELS   = new ConcurrentHashMap<String, Channel>();

    //保存请求及返回的对象
    private static final Map<String, DefaultFuture> FUTURES   = new ConcurrentHashMap<String, DefaultFuture>();


    private static ScheduledExecutorService scheduledExecutor = Executors.newSingleThreadScheduledExecutor();

    private CountDownLatch latch;
    private ScheduledFuture scheduledFuture;
    private RpcRequest fullRequest;
    private RPCCallback<T> callback;

    private RPCMessage<RPCHeader.ResponseHeader> fullResponse;
    private Throwable error;
    private Long readTimeout;

    public DefaultFuture(RpcRequest fullRequest,
                     RPCCallback<T> callback,Long readTimeout) {
        /*if (fullRequest.getResponseBodyClass() == null && callback == null) {
            log.error("responseClass or callback must have one not null only");
            return;
        }*/
        this.fullRequest = fullRequest;
        this.scheduledFuture = scheduledFuture;
        this.callback = callback;
        /*if (this.fullRequest.getResponseBodyClass() == null) {
            Type type = callback.getClass().getGenericInterfaces()[0];
            Class clazz = (Class) ((ParameterizedType) type).getActualTypeArguments()[0];
            this.fullRequest.setResponseBodyClass(clazz);
        }*/

        scheduledExecutor.schedule(new Runnable() {
            @Override
            public void run() {
                DefaultFuture rpcFuture = DefaultFuture.removeRPCFuture(fullRequest.getRequestId());
                if (rpcFuture != null) {
                    //log.debug("request timeout, logId={}, service={}, method={}",logId, serviceName, methodName);
                    rpcFuture.timeout();
                } else {
                    //log.debug("request logId={} not found", logId);
                }
            }
        }, readTimeout, TimeUnit.MILLISECONDS);

        this.latch = new CountDownLatch(1);

    }

    public void success(RPCMessage<RPCHeader.ResponseHeader> fullResponse) {
        this.fullResponse = fullResponse;
        scheduledFuture.cancel(true);
        latch.countDown();
        if (callback != null) {
            callback.success((T) fullResponse.getBodyMessage());
        }
    }

    public void fail(Throwable error) {
        this.error = error;
        scheduledFuture.cancel(true);
        latch.countDown();
        if (callback != null) {
            callback.fail(error);
        }
    }

    public void timeout() {
        this.fullResponse = null;
        latch.countDown();
        if (callback != null) {
            callback.fail(new RuntimeException("timeout"));
        }
    }

    public RPCMessage<RPCHeader.ResponseHeader> get() throws InterruptedException {
        latch.await();
        if (error != null) {
            log.warn("error occurs due to {}", error.getMessage());
            RPCHeader.RequestHeader requestHeader = fullRequest.getHeader();
            fullResponse = newResponse(requestHeader.getLogId(),
                    RPCHeader.ResCode.RES_FAIL, error.getMessage());
        }
        if (fullResponse == null) {
            fullResponse = newResponse(fullRequest.getHeader().getLogId(),
                    RPCHeader.ResCode.RES_FAIL, "time out");
        }
        return fullResponse;
    }

    public RPCMessage<RPCHeader.ResponseHeader> get(long timeout, TimeUnit unit) {
        RPCHeader.RequestHeader requestHeader = fullRequest.getHeader();
        try {
            if (latch.await(timeout, unit)) {
                if (error != null) {
                    log.warn("error occurrs due to {}", error.getMessage());
                    fullResponse = newResponse(requestHeader.getLogId(),
                            RPCHeader.ResCode.RES_FAIL, error.getMessage());
                }
            } else {
                log.warn("sync call time out");
                fullResponse = newResponse(requestHeader.getLogId(),
                        RPCHeader.ResCode.RES_FAIL, "time out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("sync call is interrupted, {}", e);
            fullResponse = newResponse(requestHeader.getLogId(),
                    RPCHeader.ResCode.RES_FAIL, "time out");
        }
        if (fullResponse == null) {
            fullResponse = newResponse(requestHeader.getLogId(),
                    RPCHeader.ResCode.RES_FAIL, "time out");
        }
        return fullResponse;
    }

    /*public Class getResponseClass() {
        return fullRequest.getResponseBodyClass();
    }*/

    private RPCMessage<RPCHeader.ResponseHeader> newResponse(
            String logId, RPCHeader.ResCode resCode, String resMsg) {
        RPCMessage<RPCHeader.ResponseHeader> fullResponse = new RPCMessage<>();
        RPCHeader.ResponseHeader responseHeader = RPCHeader.ResponseHeader.newBuilder()
                .setLogId(logId)
                .setResCode(resCode)
                .setResMsg(resMsg).build();
        fullResponse.setHeader(responseHeader);
        return fullResponse;
    }




    public static DefaultFuture getFuture(String id) {
        return FUTURES.get(id);
    }

    public static boolean hasFuture(Channel channel) {
        return CHANNELS.containsValue(channel);
    }

    public static RPCMessage<RPCHeader.RequestHeader> buildFullRequest(
            final String logId,
            final String serviceName,
            final String methodName,
            Object requestBody,
            Class responseBodyClass) {
        RPCMessage<RPCHeader.RequestHeader> fullRequest = new RPCMessage<>();

        RPCHeader.RequestHeader.Builder headerBuilder = RPCHeader.RequestHeader.newBuilder();
        headerBuilder.setLogId(logId);
        headerBuilder.setServiceName(serviceName);
        headerBuilder.setMethodName(methodName);
        fullRequest.setHeader(headerBuilder.build());
        fullRequest.setResponseBodyClass(responseBodyClass);

        if (!GeneratedMessageV3.class.isAssignableFrom(requestBody.getClass())) {
            log.error("request must be protobuf message");
            return null;
        }
        fullRequest.setBodyMessage((GeneratedMessageV3) requestBody);

        try {
            Method encodeMethod = requestBody.getClass().getMethod("toByteArray");
            byte[] bodyBytes = (byte[]) encodeMethod.invoke(requestBody);
            fullRequest.setBody(bodyBytes);
        } catch (Exception ex) {
            log.error("request object has no method toByteArray");
            return null;
        }

        return fullRequest;
    }


    public static void sent(Channel channel, RpcRequest request) {
        DefaultFuture future = FUTURES.get(request.getRequestId());
        if (future != null) {
            channel.writeAndFlush(request);
        }
    }


    public static DefaultFuture removeRPCFuture(Long id) {
        DefaultFuture future = FUTURES.get(id);
        if (future != null) {
            FUTURES.remove(id);
        }
        return future;
    }



}
