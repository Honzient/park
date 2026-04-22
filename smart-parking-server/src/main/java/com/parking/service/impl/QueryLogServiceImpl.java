package com.parking.service.impl;

import com.parking.domain.entity.QueryOperationLog;
import com.parking.mapper.QueryOperationLogMapper;
import com.parking.service.QueryLogService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class QueryLogServiceImpl implements QueryLogService {

    private final QueryOperationLogMapper queryOperationLogMapper;

    public QueryLogServiceImpl(QueryOperationLogMapper queryOperationLogMapper) {
        this.queryOperationLogMapper = queryOperationLogMapper;
    }

    @Override
    public void save(String operator, String module, String conditions, String requestUri, long costMs) {
        QueryOperationLog log = new QueryOperationLog();
        log.setOperatorName(operator);
        log.setModule(module);
        log.setQueryConditions(conditions);
        log.setRequestUri(requestUri);
        log.setCostMs(costMs);
        log.setQueryTime(LocalDateTime.now());
        queryOperationLogMapper.insert(log);
    }
}
