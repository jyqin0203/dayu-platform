package cn.edu.fudan.dayu.download.api;

/**
 * 下载事件状态：已申请、已授权、已拒绝、已传输或传输中断。
 */
public enum DownloadStatus { REQUESTED, AUTHORIZED, DENIED, DELIVERED, INTERRUPTED }
