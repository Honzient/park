package com.parking.service.impl;

import com.parking.domain.entity.LoginLog;
import com.parking.domain.entity.OperationLog;
import com.parking.mapper.LoginLogMapper;
import com.parking.mapper.OperationLogMapper;
import com.parking.service.OperationLogService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class OperationLogServiceImpl implements OperationLogService {

    private final OperationLogMapper operationLogMapper;
    private final LoginLogMapper loginLogMapper;

    public OperationLogServiceImpl(OperationLogMapper operationLogMapper, LoginLogMapper loginLogMapper) {
        this.operationLogMapper = operationLogMapper;
        this.loginLogMapper = loginLogMapper;
    }

    @Override
    public void log(String operator, String content, String requestUri, String ip, String device) {
        OperationLog log = new OperationLog();
        log.setOperatorName(operator);
        log.setOperationContent(content);
        log.setRequestUri(requestUri);
        log.setIp(ip);
        log.setDevice(device);
        log.setOperationTime(LocalDateTime.now());
        operationLogMapper.insert(log);
    }

    @Override
    public void logLogin(String username, String status, String message, String ip, String device, LocalDateTime loginTime) {
        LoginLog loginLog = new LoginLog();
        loginLog.setUsername(username);
        loginLog.setLoginStatus(status);
        loginLog.setMessage(message);
        loginLog.setIp(ip);
        loginLog.setDevice(device);
        loginLog.setLoginTime(loginTime == null ? LocalDateTime.now() : loginTime);
        loginLogMapper.insert(loginLog);
    }
}
