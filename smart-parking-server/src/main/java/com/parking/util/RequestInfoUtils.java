package com.parking.util;

import jakarta.servlet.http.HttpServletRequest;

public final class RequestInfoUtils {

    private RequestInfoUtils() {
    }

    public static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    public static String device(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String userAgent = request.getHeader("User-Agent");
        return userAgent == null ? "unknown" : userAgent;
    }

    public static String uri(HttpServletRequest request) {
        return request == null ? "unknown" : request.getRequestURI();
    }
}
