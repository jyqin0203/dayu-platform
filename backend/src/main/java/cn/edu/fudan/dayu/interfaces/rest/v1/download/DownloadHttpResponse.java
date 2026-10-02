package cn.edu.fudan.dayu.interfaces.rest.v1.download;

import cn.edu.fudan.dayu.download.api.DownloadContent;
import cn.edu.fudan.dayu.shared.kernel.*;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;

/** v1 与 Legacy 可复用的安全传输响应；Spring 发送完成或失败时关闭 local 输入流。 */
public final class DownloadHttpResponse {
    private DownloadHttpResponse() {}
    public static ResponseEntity<Resource> from(DownloadContent content) {
        try {
            var grant = content.grant();
            String name = grant.downloadFileName();
            if (name == null || name.isBlank() || name.chars().anyMatch(Character::isISOControl)) throw invalid();
            var response = ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString())
                    .contentType(MediaType.parseMediaType("application/x-netcdf"))
                    .contentLength(grant.expectedBytes()).header(HttpHeaders.CACHE_CONTROL, "private, no-store");
            if (content.stream() != null) return response.body(new InputStreamResource(content.stream()));
            String location = grant.internalLocation();
            if (location == null || !location.startsWith("/") || location.startsWith("//")
                    || location.chars().anyMatch(Character::isISOControl)) throw invalid();
            URI uri = URI.create(location);
            if (uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getAuthority() != null) throw invalid();
            return response.header("X-Accel-Redirect", location).build();
        } catch (RuntimeException e) {
            // 响应尚未交给 Spring 时发生转换错误，必须由本方法关闭已打开的流。
            if (content.stream() != null) try { content.stream().close(); } catch (IOException ignored) { }
            throw e instanceof BusinessException ? e : invalid();
        }
    }
    private static BusinessException invalid() { return new BusinessException(ErrorCode.INTERNAL_ERROR, "下载响应配置不可用"); }
}
