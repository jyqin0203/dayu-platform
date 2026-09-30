package cn.edu.fudan.dayu.discovery.api;

import cn.edu.fudan.dayu.shared.kernel.AssetId;
import java.time.Instant;

/**
 * 当前预览可能对应的 NetCDF 下载候选摘要。
 * 它只帮助用户选择资产，不代表已经获得下载授权。
 */
public record DownloadCandidate(AssetId assetId, String fileName, long fileSize, Instant validTime) {}
