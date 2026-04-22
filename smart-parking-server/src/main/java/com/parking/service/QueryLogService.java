package com.parking.service;

public interface QueryLogService {

    void save(String operator, String module, String conditions, String requestUri, long costMs);
}
