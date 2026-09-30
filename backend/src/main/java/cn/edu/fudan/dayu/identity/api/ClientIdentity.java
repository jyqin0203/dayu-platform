package cn.edu.fudan.dayu.identity.api;

/**
 * 登录时由接入层提供的客户端 IP 和 User-Agent 信息。
 */
public record ClientIdentity(String ipAddress, String userAgent) {}
