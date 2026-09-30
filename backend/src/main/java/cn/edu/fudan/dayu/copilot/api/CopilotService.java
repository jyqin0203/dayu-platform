package cn.edu.fudan.dayu.copilot.api;

import cn.edu.fudan.dayu.shared.kernel.ActorContext;
import java.util.Optional;

/**
 * Copilot 模块对外公开的自然语言辅助查询接口。
 * Copilot 只能调用 Catalog 和 Discovery 获取真实结果，不能执行下载或管理操作。
 */
public interface CopilotService {
    /**
     * 解释自然语言、校验结构化条件并根据真实业务查询结果生成回答。
     *
     * @param command 用户问题、时区和页面上下文
     * @param actor 可选的当前登录身份，不包含 Session 或其他敏感信息
     * @return 查询解释、真实结果说明和前端建议动作
     */
    CopilotResponse query(CopilotCommand command, Optional<ActorContext> actor);
}
