package me.j360.rpc.client;

import com.google.common.collect.Lists;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import lombok.extern.slf4j.Slf4j;
import me.j360.rpc.client.handler.RPCClientHandler;
import me.j360.rpc.codec.protostuff.RpcDecoder;
import me.j360.rpc.codec.protostuff.RpcEncoder;
import me.j360.rpc.codec.protostuff.RpcRequest;
import me.j360.rpc.codec.protostuff.RpcResponse;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * Package: me.j360.rpc.client
 * User: min_xu
 * Date: 2017/5/23 下午1:25
 * 说明：单例类
 */

@Slf4j
public class RPCConnectManager {

    private RPCClientOption rpcClientOption;


    /**
     * 因为该rpc只使用单个协议作为传输,所以只需要定义一个Client实例
     * 服务集合内存表,根据依赖的多个服务,一个服务有多个实例
     */
    private static ConcurrentHashMap<String,List<InetSocketAddress>> providerMap = new ConcurrentHashMap<>();
    private static ConcurrentHashMap<InetSocketAddress,Channel> channelMap = new ConcurrentHashMap<>();

    private volatile static RPCConnectManager rpcConnectManager;


    private CountDownLatch latch = new CountDownLatch(1);
    private RPCConnectManager(RPCClientOption rpcClientOption) {
        this.rpcClientOption = rpcClientOption;
    }

    public static RPCConnectManager getInstance(RPCClientOption rpcClientOption) {
        if (rpcConnectManager == null) {
            synchronized (RPCConnectManager.class) {
                if (rpcConnectManager == null) {
                    rpcConnectManager = new RPCConnectManager(rpcClientOption);
                }
            }
        }

        return rpcConnectManager;
    }

    /**
     * 调用入口
     * @return
     */
    public Channel selectChannel(String interfaceName) {

        //选择,增加判断是否有已经注册并连接上的channel
        List<InetSocketAddress> list = providerMap.get(interfaceName);

        if (null == list) {
            try {
                latch.await();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        InetSocketAddress address = select(list);

        return channelMap.get(address);
    }


    /**
     * 入口,添加服务远程连接
     */
    public void addNewConnection(String interfaceName, InetSocketAddress remoteAddress) {

        //需要去重判断
        if (!channelMap.containsKey(remoteAddress)) {
            Bootstrap bootstrap = newBootstrap();
            connect(bootstrap, remoteAddress, interfaceName);
        } else {
            Channel channel = channelMap.get(remoteAddress);
            if (!channel.isActive()) {

            }
        }
    }

    /**
     * 入口,删除服务远程连接
     */
    public void removeConnection(String interfaceName, InetSocketAddress remoteAddress) {

        //删除channel,删除服务列表
        Channel channel = channelMap.get(remoteAddress);
        if (null != channel) {
            close(channel);
        }

    }

    private void close(Channel channel) {
        channel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
    }


    protected Bootstrap newBootstrap() {
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.channel(NioSocketChannel.class);
        bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, rpcClientOption.getConnectTimeoutMillis());
        bootstrap.option(ChannelOption.SO_KEEPALIVE, rpcClientOption.isKeepAlive());
        bootstrap.option(ChannelOption.SO_REUSEADDR, rpcClientOption.isReuseAddr());
        bootstrap.option(ChannelOption.TCP_NODELAY, rpcClientOption.isTcpNoDelay());
        bootstrap.option(ChannelOption.SO_RCVBUF, rpcClientOption.getReceiveBufferSize());
        bootstrap.option(ChannelOption.SO_SNDBUF, rpcClientOption.getSendBufferSize());

        ChannelInitializer<SocketChannel> initializer = new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel channel) throws Exception {
                channel.pipeline()
                        .addLast(new RpcEncoder(RpcRequest.class))
                        .addLast(new LengthFieldBasedFrameDecoder(65536,0,4,0,0))
                        .addLast(new RpcDecoder(RpcResponse.class))
                        .addLast(new RPCClientHandler());
            }
        };
        bootstrap.group(new NioEventLoopGroup()).handler(initializer);
        return bootstrap;
    }


    /**
     * 重连
     * @param channel
     * @param remoteAddress
     */
    private void reconnect(Channel channel, InetSocketAddress remoteAddress) {
        try {
            final ChannelFuture future = channel.connect(remoteAddress);
            future.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture channelFuture) throws Exception {
                    if (channelFuture.isSuccess()) {

                        //需要去重判断
                        channelMap.put(remoteAddress,channelFuture.channel());

                        log.debug("Connection {} is established", channelFuture.channel());
                    } else {
                        log.warn(String.format("Connection get failed on {} due to {}",
                                channelFuture.cause().getMessage(), channelFuture.cause()));
                    }
                }
            });
            future.awaitUninterruptibly();
            if (future.isSuccess()) {
                log.debug("connect {} success", remoteAddress.toString());

            } else {
                log.warn("connect {} failed", remoteAddress.toString());

            }
        } catch (Exception e) {
            log.error("failed to connect to {} due to {}", remoteAddress.toString(), e.getMessage());
        }
    }


    private Channel connect(Bootstrap bootstrap,InetSocketAddress remoteAddress, String interfaceName) {
        try {
            final ChannelFuture future = bootstrap.connect(remoteAddress);
            future.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture channelFuture) throws Exception {
                    if (channelFuture.isSuccess()) {

                        //需要去重判断
                        channelMap.put(remoteAddress,channelFuture.channel());

                        if (providerMap.containsKey(interfaceName)) {
                            providerMap.get(interfaceName).add(remoteAddress);
                        } else {
                            List<InetSocketAddress> list = Lists.newArrayList();
                            list.add(remoteAddress);
                            providerMap.put(interfaceName,list);
                        }

                        latch.countDown();
                        log.debug("Connection {} is established", channelFuture.channel());
                    } else {
                        log.warn(String.format("Connection get failed on {} due to {}",
                                channelFuture.cause().getMessage(), channelFuture.cause()));
                    }
                }
            });
            future.awaitUninterruptibly();
            if (future.isSuccess()) {
                log.debug("connect {} success", remoteAddress.toString());
                return future.channel();
            } else {
                log.warn("connect {} failed", remoteAddress.toString());
                return null;
            }
        } catch (Exception e) {
            log.error("failed to connect to {} due to {}", remoteAddress.toString(), e.getMessage(),e);
            return null;
        }
    }

    /**
     * 选择算法模拟方法
     * 轮训
     * 哈希
     * @return
     */
    private InetSocketAddress select(List<InetSocketAddress> list){
        return list.get(0);
    }
}
