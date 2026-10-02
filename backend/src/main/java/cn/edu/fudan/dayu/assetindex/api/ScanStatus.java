package cn.edu.fudan.dayu.assetindex.api;

/** 持久化扫描任务状态；PARTIAL 表示至少一个根或文件失败。 */
public enum ScanStatus { RUNNING, SUCCEEDED, PARTIAL, FAILED }
