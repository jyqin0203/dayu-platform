package cn.edu.fudan.dayu.download.api;

import cn.edu.fudan.dayu.shared.kernel.ActorContext;
import cn.edu.fudan.dayu.shared.kernel.DownloadEventId;

/** 为 v1 和 Legacy 共用的受控文件传输入口，不接受客户端路径。 */
public interface DownloadContentService {
    /** 验证事件属于当前用户、未过期且文件未变化；返回流由调用方关闭，Nginx 模式只返回内部授权。 */
    DownloadContent prepareContent(DownloadEventId eventId, ActorContext actor);
}
