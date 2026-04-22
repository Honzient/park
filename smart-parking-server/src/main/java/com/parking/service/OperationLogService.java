package com.parking.service;

import java.time.LocalDateTime;

public interface OperationLogService {

    void log(String operator, String content, String requestUri, String ip, String device);

    void logLogin(String username, String status, String message, String ip, String device, LocalDateTime loginTime);
}
