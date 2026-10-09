package com.intelligentrecruitment.shared.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentrecruitment.shared.error.ApiException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Set;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.converter.json.AbstractJackson2HttpMessageConverter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;

/** Rejects attempts to steer server-owned Agent routing through public business APIs. */
@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class RouteControlFieldRequestBodyAdvice extends org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter {
    private static final Set<String> RESERVED = Set.of("agentid","operation","routeconfigversion","selectionsource","parentrunid","executionsource","cutoverid","validationbypass","complexagentenabled","recruitmentcomplexagentenabled","inputhash","attemptid","authorizationid","grantid","reservationid","modelid","tenantid","actorid","pricingmethod","billingmethod");
    private final ObjectMapper objectMapper;

    public RouteControlFieldRequestBodyAdvice(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    @Override
    public boolean supports(MethodParameter parameter, java.lang.reflect.Type targetType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return AbstractJackson2HttpMessageConverter.class.isAssignableFrom(converterType);
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage inputMessage, MethodParameter parameter,
                                           java.lang.reflect.Type targetType,
                                           Class<? extends HttpMessageConverter<?>> converterType) throws IOException {
        if (!(inputMessage instanceof ServletServerHttpRequest servletRequest)) return inputMessage;
        String path = servletRequest.getServletRequest().getRequestURI();
        if (!path.startsWith("/api/v1/tenants/") && !path.startsWith("/api/v1/recruitment/")) return inputMessage;

        byte[] body = inputMessage.getBody().readAllBytes();
        if (body.length == 0) return inputMessage;
        JsonNode root;
        try { root = objectMapper.readTree(body); }
        catch (IOException invalidJson) { throw new HttpMessageNotReadableException("请求 JSON 格式无效", invalidJson, inputMessage); }
        if (root != null && containsReservedField(root)) {
            throw new ApiException("CLIENT_ROUTE_CONTROL_FORBIDDEN", "Agent 路由由服务端配置，客户端不能提交路由控制字段", HttpStatus.BAD_REQUEST);
        }
        return new HttpInputMessage() {
            @Override public HttpHeaders getHeaders() { return inputMessage.getHeaders(); }
            @Override public java.io.InputStream getBody() { return new ByteArrayInputStream(body); }
        };
    }

    static boolean containsReservedField(JsonNode root) {
        if(root==null)return false;
        if(root.isObject()){
            var names=root.fieldNames();while(names.hasNext())if(RESERVED.contains(normalize(names.next())))return true;
            var values=root.elements();while(values.hasNext())if(containsReservedField(values.next()))return true;
        } else if(root.isArray())for(JsonNode item:root)if(containsReservedField(item))return true;
        return false;
    }
    static String normalize(String name){return name==null?"":name.replace("_","").replace("-","").toLowerCase(java.util.Locale.ROOT);}
}
