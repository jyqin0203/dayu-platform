package cn.edu.fudan.dayu.download.api;

/**
 * 预留的下载传输结果回写接口。
 * 未来可根据 Nginx 日志补充实际发送字节数和完成状态。
 */
public interface DeliveryResultRecorder {
    /** 记录一次授权后的实际文件传输结果。 */
    void recordDeliveryResult(DeliveryResultCommand command);
}
