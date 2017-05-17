package me.j360.rpc.core;

import lombok.Data;

/**
 * Package: me.j360.rpc.core
 * User: min_xu
 * Date: 2017/5/17 下午1:15
 * 说明：
 */

@Data
public class Consumer {

    private String serviceName;

    private IpPort ipPort;

}
