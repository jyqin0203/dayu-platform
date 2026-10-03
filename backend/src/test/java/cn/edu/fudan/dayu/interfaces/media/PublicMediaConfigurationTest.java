package cn.edu.fudan.dayu.interfaces.media;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.nio.file.*;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.PathResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Pure MVC/filesystem checks with synthetic bytes: no database, scanning, production data, or model requests. */
class PublicMediaConfigurationTest {
    @TempDir Path temporary;
    @Configuration @EnableWebMvc static class Mvc {}

    private AnnotationConfigWebApplicationContext context(boolean enabled,Path webp,Path colorbars,Path nc,String prefix) {
        var context=new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.setEnvironment(new MockEnvironment().withProperty("dayu.media.enabled",String.valueOf(enabled))
                .withProperty("dayu.indexing.roots.webp-preview",webp==null ? "" : webp.toString())
                .withProperty("dayu.catalog.colorbar-root",colorbars==null ? "" : colorbars.toString())
                .withProperty("dayu.indexing.roots.netcdf-data",nc==null ? "" : nc.toString())
                .withProperty("dayu.preview.public-prefix",prefix));
        context.register(Mvc.class,PublicMediaConfiguration.class);
        try { context.refresh(); return context; }
        catch (RuntimeException failure) { context.close(); throw failure; }
    }

    @Test void servesOnlyWhitelistedImagesAndPreservesLegacyColorbarUrls() throws Exception {
        Path webp=Files.createDirectory(temporary.resolve("webp"));
        Path colors=Files.createDirectories(temporary.resolve("public/CPP_Colorbar/vertical"));
        byte[] bytes="synthetic image bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(webp.resolve("frame.webp"),bytes);
        Files.write(webp.resolve("secret.nc"),bytes);
        Files.write(webp.resolve(".env"),bytes);
        Files.write(webp.resolve("active.svg"),bytes);
        Files.write(colors.resolve("CTH_Colorbar.webp"),bytes);
        try(var context=context(true,webp,temporary.resolve("public"),null,"/media/webp/")) {
            var mvc=MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(get("/media/webp/frame.webp")).andExpect(status().isOk())
                    .andExpect(content().contentType("image/webp")).andExpect(content().bytes(bytes));
            mvc.perform(head("/media/webp/frame.webp")).andExpect(status().isOk()).andExpect(content().string(""));
            mvc.perform(get("/WebP/WebP_V2_Dpi500_4KM/frame.webp")).andExpect(status().isOk())
                    .andExpect(content().contentType("image/webp")).andExpect(content().bytes(bytes));
            mvc.perform(get("/CPP_Colorbar/vertical/CTH_Colorbar.webp")).andExpect(status().isOk())
                    .andExpect(content().bytes(bytes));
            for(String path:new String[]{"secret.nc",".env","active.svg","missing.webp",""})
                mvc.perform(get("/media/webp/"+path)).andExpect(status().isNotFound());
            mvc.perform(get("/netcdf/secret.nc")).andExpect(status().isNotFound());
        }
    }

    @Test void disabledAndUnconfiguredMediaExposeNothing() throws Exception {
        Files.writeString(temporary.resolve("frame.webp"),"test");
        for(boolean enabled:new boolean[]{false,true}) {
            try(var context=context(enabled,enabled ? null : temporary,null,null,"/media/webp/")) {
                MockMvcBuilders.webAppContextSetup(context).build().perform(get("/media/webp/frame.webp"))
                        .andExpect(status().isNotFound());
            }
        }
    }

    @Test void preventsParentAbsoluteEncodedAndHiddenPaths() throws Exception {
        var resolver=new ConfinedImageResolver(Set.of("webp"));
        var root=new PathResource(temporary);
        for(String path:new String[]{"../outside.webp","/outside.webp","%2e%2e/outside.webp",
                "sub\\outside.webp","C:/outside.webp",".hidden/frame.webp","sub//frame.webp"})
            assertThat(resolver.getResource(path,root)).isNull();
    }

    @Test void refusesOverlappingPublicAndScientificRootsAndUnsafePrefix() throws Exception {
        Path nc=Files.createDirectories(temporary.resolve("nc"));
        assertThatThrownBy(() -> context(true,temporary,null,nc,"/media/webp/"))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> context(true,temporary,null,null,"/api/"))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }
}
