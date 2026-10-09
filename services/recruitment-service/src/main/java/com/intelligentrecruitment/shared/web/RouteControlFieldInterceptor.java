package com.intelligentrecruitment.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Applies the same reserved-control-field rule to query/form parameters and control headers. */
@Component
public final class RouteControlFieldInterceptor implements HandlerInterceptor {
    private static final Set<String> CONTROL_HEADERS=Set.of("x-ir-agent-id","x-ir-operation","x-ir-route-config-version","x-ir-selection-source","x-ir-attempt-id","x-ir-input-hash","x-ir-model-id");
    @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler){
        String path=request.getRequestURI();
        if(!path.startsWith("/api/v1/tenants/")&&!path.startsWith("/api/v1/recruitment/"))return true;
        var names=request.getParameterMap().keySet();
        if(names.stream().anyMatch(name->isReserved(name))||request.getHeaderNames()!=null&&java.util.Collections.list(request.getHeaderNames()).stream().map(name->name.toLowerCase(java.util.Locale.ROOT)).anyMatch(CONTROL_HEADERS::contains))
            throw new com.intelligentrecruitment.shared.error.ApiException("CLIENT_ROUTE_CONTROL_FORBIDDEN","Agent 路由由服务端配置，客户端不能提交路由控制字段",HttpStatus.BAD_REQUEST);
        return true;
    }
    private boolean isReserved(String name){
        String normalized=RouteControlFieldRequestBodyAdvice.normalize(name);
        return Set.of("agentid","operation","routeconfigversion","selectionsource","parentrunid","executionsource","cutoverid","validationbypass","complexagentenabled","recruitmentcomplexagentenabled","inputhash","attemptid","authorizationid","grantid","reservationid","modelid","tenantid","actorid","pricingmethod","billingmethod").contains(normalized);
    }
}
