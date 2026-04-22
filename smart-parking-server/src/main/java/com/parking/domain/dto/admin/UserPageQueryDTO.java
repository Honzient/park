package com.parking.domain.dto.admin;

import lombok.Data;

@Data
public class UserPageQueryDTO {
    private long pageNo = 1;
    private long pageSize = 20;
    private String keyword;
}
