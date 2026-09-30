package cn.edu.fudan.dayu.download.api;

/**
 * 下载授权时由 HTTP 接入层提供的客户端 IP 和 User-Agent 信息。
 */
public record ClientContext(String ipAddress, String userAgent) {}
