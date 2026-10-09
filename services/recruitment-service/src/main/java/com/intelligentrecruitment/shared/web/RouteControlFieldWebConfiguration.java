package com.intelligentrecruitment.shared.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class RouteControlFieldWebConfiguration implements WebMvcConfigurer {
    private final RouteControlFieldInterceptor interceptor;
    public RouteControlFieldWebConfiguration(RouteControlFieldInterceptor interceptor){this.interceptor=interceptor;}
    @Override public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(interceptor);}
}
