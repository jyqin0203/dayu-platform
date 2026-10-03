package cn.edu.fudan.dayu.download.api;

import cn.edu.fudan.dayu.shared.kernel.ActorContext;
import cn.edu.fudan.dayu.shared.kernel.DownloadEventId;

/** 使用已创建的短期授权取得内部文件传输信息。 */
public interface DownloadGrantAccess {
    DownloadGrant getAuthorizedGrant(DownloadEventId eventId, ActorContext actor);
}
