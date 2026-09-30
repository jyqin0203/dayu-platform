package cn.edu.fudan.dayu.download.api;

import cn.edu.fudan.dayu.shared.kernel.AssetId;

/**
 * 用户申请下载时提交的资产编号和数据使用目的。
 * 前端不得提交用户身份或服务器文件路径。
 */
public record DownloadCommand(AssetId assetId, String purpose) {}
