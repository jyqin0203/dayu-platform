package cn.edu.fudan.dayu.assetindex.api;

/**
 * 索引扫描的触发来源：应用启动、定时任务或管理员手动触发。
 */
public enum ScanTrigger { STARTUP, SCHEDULED, MANUAL }
