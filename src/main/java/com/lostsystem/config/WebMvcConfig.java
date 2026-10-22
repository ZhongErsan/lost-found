package com.lostsystem.config;

import com.lostsystem.auth.AuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    @Value("${lost-found.upload.path:./data/uploads}")
    private String uploadPath;
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                /*
                 * 保护未来统一的 /api/** 接口，
                 * 同时保护当前已经存在的 /ai/** 接口。
                 */
                .addPathPatterns("/api/**", "/ai/**")

                /*
                 * 登录、注册允许匿名访问，其他认证接口仍需登录。
                 */
                .excludePathPatterns(
                        "/api/auth/login",
                        "/api/auth/register"
                );
    }
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = Path.of(uploadPath)
                .toAbsolutePath()
                .normalize()
                .toUri()
                .toString();

        if (!location.endsWith("/")) {
            location += "/";
        }

        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(location);
    }
}
