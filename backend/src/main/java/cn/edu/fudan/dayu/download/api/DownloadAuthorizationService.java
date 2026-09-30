package cn.edu.fudan.dayu.download.api;

import cn.edu.fudan.dayu.shared.kernel.ActorContext;

/**
 * Download 模块对外公开的 NetCDF 下载授权接口。
 * 授权会校验用户、用途和资产并记录审计，但不由 Java 传输整个大文件。
 */
public interface DownloadAuthorizationService {
    /**
     * 为当前用户申请指定资产的下载授权。
     *
     * @param command 资产编号和用户填写的用途
     * @param actor 由认证接入层创建的可信操作人
     * @param client 由接入层取得的客户端信息
     * @return 供服务器内部完成文件传输的授权结果
     */
    DownloadGrant authorizeDownload(DownloadCommand command, ActorContext actor, ClientContext client);
}
