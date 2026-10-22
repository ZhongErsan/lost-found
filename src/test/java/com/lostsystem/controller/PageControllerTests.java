package com.lostsystem.controller;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.Properties;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

class PageControllerTests {

    @Test
    void homeRoutesRenderTheConfiguredTemplateWithoutExternalServices() throws Exception {
        MockMvc mvc = pageMvc();

        for (String route : new String[]{"/", "/index", "/index.html"}) {
            mvc.perform(get(route))
                    .andExpect(status().isOk())
                    .andExpect(view().name("index"))
                    .andExpect(content().string(containsString("寻物 · 失物招领助手")))
                    .andExpect(content().string(containsString("href=\"/css/index.css\"")))
                    .andExpect(content().string(containsString("src=\"/js/auth.js\"")))
                    .andExpect(content().string(containsString("src=\"/js/index.js\"")))
                    .andExpect(content().string(containsString("src=\"/js/claims.js\"")))
                    .andExpect(content().string(containsString("id=\"mode-backfill\"")))
                    .andExpect(content().string(not(containsString("th:href="))));
        }
    }

    @Test
    void loginRoutesRenderAndRespectTheApplicationContextPath() throws Exception {
        MockMvc mvc = pageMvc();
        for (String route : new String[]{"/login", "/login.html"}) {
            mvc.perform(get("/lost-found" + route).contextPath("/lost-found"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("login"))
                    .andExpect(content().string(containsString("登录 · 寻物")))
                    .andExpect(content().string(containsString("content=\"/lost-found/\"")))
                    .andExpect(content().string(containsString("src=\"/lost-found/js/auth.js\"")))
                    .andExpect(content().string(containsString("action=\"/lost-found/api/auth/login\"")))
                    .andExpect(content().string(not(containsString("th:action="))));
        }
    }

    private MockMvc pageMvc() throws java.io.IOException {
        Properties properties = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));
        ClassLoaderTemplateResolver templates = new ClassLoaderTemplateResolver();
        templates.setPrefix(properties.getProperty("spring.thymeleaf.prefix").replace("classpath:/", ""));
        templates.setSuffix(properties.getProperty("spring.thymeleaf.suffix"));
        templates.setTemplateMode(properties.getProperty("spring.thymeleaf.mode"));
        templates.setCharacterEncoding(properties.getProperty("spring.thymeleaf.encoding"));
        templates.setCacheable(Boolean.parseBoolean(properties.getProperty("spring.thymeleaf.cache")));

        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(templates);
        ThymeleafViewResolver views = new ThymeleafViewResolver();
        views.setTemplateEngine(engine);
        views.setCharacterEncoding("UTF-8");
        return MockMvcBuilders.standaloneSetup(new PageController()).setViewResolvers(views).build();
    }
}
