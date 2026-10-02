package cn.edu.fudan.dayu.download.api;

import java.io.InputStream;

/**
 * 通过本人、期限、文件重验后取得的传输资源。local 模式的 stream 已打开，调用方必须关闭，
 * HTTP 接入层交给 Spring InputStreamResource 写出并关闭；nginx 模式 stream=null，未打开本地流。
 * grant 中的 internalLocation 只供内部响应头使用，不得序列化到公开 JSON。
 */
public record DownloadContent(DownloadGrant grant, InputStream stream) {}
