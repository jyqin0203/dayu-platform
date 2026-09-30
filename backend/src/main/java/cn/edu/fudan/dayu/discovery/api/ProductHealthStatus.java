package cn.edu.fudan.dayu.discovery.api;

/**
 * 产品数据健康状态：正常、延迟、缺失或产品已停用。
 */
public enum ProductHealthStatus { HEALTHY, STALE, MISSING, DISABLED }
