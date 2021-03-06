package me.j360.rpc.spring;

import me.j360.rpc.client.RPCClient;
import me.j360.rpc.client.RPCClientOption;
import me.j360.rpc.register.ServiceDiscovery;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Package: me.j360.rpc.spring
 * User: min_xu
 * Date: 2017/5/23 下午5:39
 * 说明：
 */

@Configuration
public class RPCClientConfiguration {

    //private @Value("#{timeout}") Long timeout;
    //private @Value("#{zkAddress}") String zkAddress;
    private  String zkAddress = "127.0.0.1:2181";

    @Bean
    public RPCClientOption rpcClientOption() {


        return new RPCClientOption();
    }



    @Bean
    public RPCClientFactoryBean rpcClientFactoryBean() {
        return null;
    }

    @Bean
    public RPCClient rpcClient() {
        try {
            return rpcClientFactoryBean().getObject();
        } catch (Exception e) {
            return null;
        }

    }

    @Bean
    public ServiceDiscovery serviceDiscovery() {
        return new ServiceDiscovery(rpcClient(),zkAddress);
    }
}
