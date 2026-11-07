package com.campustaximate.server.web;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AccessInterceptor access;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(access).addPathPatterns("/api/**");
    }

    /** 화면 주소를 새로고침해도 앱이 열리게 한다. */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        for (String path : new String[] {"/history", "/request/school", "/request/home", "/parties/{id}"}) {
            registry.addViewController(path).setViewName("forward:/index.html");
        }
    }
}
