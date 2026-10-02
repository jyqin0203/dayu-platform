package cn.edu.fudan.dayu.interfaces.rest.v1.download;

import cn.edu.fudan.dayu.download.api.ClientContext;
import cn.edu.fudan.dayu.download.api.DownloadAuthorizationService;
import cn.edu.fudan.dayu.download.api.DownloadCommand;
import cn.edu.fudan.dayu.download.api.DownloadGrant;
import cn.edu.fudan.dayu.download.api.DownloadGrantAccess;
import cn.edu.fudan.dayu.interfaces.rest.v1.CurrentActorProvider;
import cn.edu.fudan.dayu.shared.kernel.AssetId;
import cn.edu.fudan.dayu.shared.kernel.BusinessException;
import cn.edu.fudan.dayu.shared.kernel.DownloadEventId;
import cn.edu.fudan.dayu.shared.kernel.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** NC 下载授权和 Nginx 内部传输入口。 */
@RestController
@RequestMapping("/api/v1/downloads")
public class DownloadController {
    private static final Duration GRANT_TTL = Duration.ofMinutes(5);
    private final DownloadAuthorizationService authorizations;
    private final DownloadGrantAccess grants;
    private final CurrentActorProvider actors;

    public DownloadController(DownloadAuthorizationService authorizations,
                              DownloadGrantAccess grants, CurrentActorProvider actors) {
        this.authorizations = authorizations;
        this.grants = grants;
        this.actors = actors;
    }

    @PostMapping
    public ResponseEntity<AuthorizationResponse> authorize(
            @Valid @RequestBody AuthorizationRequest body, HttpServletRequest request) {
        DownloadGrant grant = authorizations.authorizeDownload(
                new DownloadCommand(new AssetId(body.assetId()), body.purpose().trim()),
                actors.required(),
                new ClientContext(request.getRemoteAddr(), request.getHeader("User-Agent")));
        return ResponseEntity.status(HttpStatus.CREATED).body(new AuthorizationResponse(
                grant.eventId().value(), URI.create("/api/v1/downloads/" + grant.eventId().value() + "/content"),
                grant.downloadFileName(), grant.expectedBytes(), grant.authorizedAt().plus(GRANT_TTL)));
    }

    @GetMapping("/{downloadEventId}/content")
    public ResponseEntity<Void> content(@PathVariable long downloadEventId) {
        DownloadGrant grant = grants.getAuthorizedGrant(
                new DownloadEventId(downloadEventId), actors.required());
        if (Instant.now().isAfter(grant.authorizedAt().plus(GRANT_TTL))) {
            throw new BusinessException(ErrorCode.ASSET_GONE, "下载授权已过期");
        }
        return ResponseEntity.ok()
                .header("X-Accel-Redirect", grant.internalLocation())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + grant.downloadFileName().replace("\"", "_") + "\"")
                .header(HttpHeaders.CONTENT_TYPE, grant.contentType())
                .header(HttpHeaders.CONTENT_LENGTH, Long.toString(grant.expectedBytes()))
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .build();
    }

    public record AuthorizationRequest(
            @Min(1) long assetId,
            @NotBlank @Size(min = 10, max = 2000) String purpose) {}
    public record AuthorizationResponse(
            long downloadEventId, URI downloadUrl, String fileName,
            long expectedBytes, Instant expiresAt) {}
}
