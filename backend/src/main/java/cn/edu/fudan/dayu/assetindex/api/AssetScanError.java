package cn.edu.fudan.dayu.assetindex.api;

/**
 * 单个文件在索引扫描中的安全错误信息。
 * 只记录相对路径和脱敏消息，不暴露生产绝对路径或异常堆栈。
 */
public record AssetScanError(String relativePath, String errorCode, String safeMessage) {}
