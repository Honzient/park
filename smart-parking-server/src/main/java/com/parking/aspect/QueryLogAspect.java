package com.parking.aspect;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parking.annotation.QueryLog;
import com.parking.security.SecurityUtils;
import com.parking.service.QueryLogService;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

@Aspect
@Component
public class QueryLogAspect {

    private final QueryLogService queryLogService;
    private final ObjectMapper objectMapper;

    public QueryLogAspect(QueryLogService queryLogService, ObjectMapper objectMapper) {
        this.queryLogService = queryLogService;
        this.objectMapper = objectMapper;
    }

    @Around("@annotation(queryLog)")
    public Object around(ProceedingJoinPoint joinPoint, QueryLog queryLog) throws Throwable {
        long start = System.currentTimeMillis();
        String operator = SecurityUtils.getCurrentUsername().orElse("anonymous");
        String conditions = serializeArgs(joinPoint.getArgs());
        String requestUri = getRequestUri();

        try {
            return joinPoint.proceed();
        } finally {
            try {
                queryLogService.save(operator, queryLog.module(), conditions, requestUri, System.currentTimeMillis() - start);
            } catch (Exception ignored) {
            }
        }
    }

    private String serializeArgs(Object[] args) {
        List<Object> filteredArgs = new ArrayList<>();
        for (Object arg : args) {
            if (arg == null) {
                continue;
            }
            if (arg instanceof ServletRequest || arg instanceof ServletResponse || arg instanceof MultipartFile) {
                continue;
            }
            filteredArgs.add(arg);
        }

        try {
            return objectMapper.writeValueAsString(filteredArgs);
        } catch (JsonProcessingException exception) {
            return "[unserializable arguments]";
        }
    }

    private String getRequestUri() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return "unknown";
        }
        return attributes.getRequest().getRequestURI();
    }
}
