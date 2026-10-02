package cn.edu.fudan.dayu.interfaces.rest.legacy;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** 证明旧直接下载与 v1 下载共用 Session/CSRF 安全链。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("skeleton")
class LegacyDownloadSecurityTest {
    @Autowired MockMvc mvc;

    @Test
    void downloadRequiresAuthenticationBeforeController() throws Exception {
        mvc.perform(post("/api/download.php").with(csrf())
                        .param("file_path", "netcdf/forecast/202609020600/sample.nc")
                        .param("purpose", "用于研究区域降水变化"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.ok").value(false));
    }

    @Test
    void authenticatedDownloadStillRequiresCsrfBeforeController() throws Exception {
        mvc.perform(post("/api/download.php").with(user("legacy-user").roles("USER"))
                        .param("file_path", "netcdf/forecast/202609020600/sample.nc")
                        .param("purpose", "用于研究区域降水变化"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.ok").value(false));
    }
}
