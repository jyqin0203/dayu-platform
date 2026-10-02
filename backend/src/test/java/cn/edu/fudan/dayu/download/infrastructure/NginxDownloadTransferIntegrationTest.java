package cn.edu.fudan.dayu.download.infrastructure;

import static org.assertj.core.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;

/** 真实 Nginx X-Accel 字节与 internal 隔离测试；81端口仅模拟后端授权响应，不验证 Java 身份。 */
class NginxDownloadTransferIntegrationTest {
    @Test void servesInternalBytesOnlyAfterUpstreamAuthorization() throws Exception {
        byte[] bytes = "synthetic nc transfer evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String config = """
                server {
                    listen 80;
                    location /api/authorized { proxy_pass http://127.0.0.1:81/granted; }
                    location /api/denied { proxy_pass http://127.0.0.1:81/denied; }
                    location ^~ /internal-netcdf/ {
                        internal;
                        alias /data/;
                        disable_symlinks on;
                        default_type application/x-netcdf;
                        add_header Cache-Control "private, no-store" always;
                    }
                    location ^~ /netcdf/ { return 404; }
                }
                server {
                    listen 127.0.0.1:81;
                    location /granted {
                        add_header X-Accel-Redirect /internal-netcdf/sample.nc;
                        add_header Content-Disposition 'attachment; filename="sample.nc"';
                        return 200;
                    }
                    location /denied { return 403; }
                }
                """;
        try (GenericContainer<?> nginx = new GenericContainer<>("nginx:1.26-alpine")
                .withExposedPorts(80).withStartupTimeout(Duration.ofMinutes(3))
                .withCopyToContainer(Transferable.of(config), "/etc/nginx/conf.d/default.conf")
                .withCopyToContainer(Transferable.of(bytes), "/data/sample.nc")) {
            nginx.start();
            String base = "http://" + nginx.getHost() + ":" + nginx.getMappedPort(80);
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpResponse<byte[]> authorized = get(client, base + "/api/authorized");
            assertThat(authorized.statusCode()).isEqualTo(200);
            assertThat(authorized.body()).isEqualTo(bytes);
            assertThat(authorized.headers().firstValue("Content-Disposition")).contains("attachment; filename=\"sample.nc\"");
            assertThat(authorized.headers().firstValue("X-Accel-Redirect")).isEmpty();
            assertThat(get(client, base + "/internal-netcdf/sample.nc").statusCode()).isEqualTo(404);
            assertThat(get(client, base + "/netcdf/sample.nc").statusCode()).isEqualTo(404);
            assertThat(get(client, base + "/api/denied").statusCode()).isEqualTo(403);
        }
    }
    private static HttpResponse<byte[]> get(HttpClient client, String uri) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(20)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }
}
