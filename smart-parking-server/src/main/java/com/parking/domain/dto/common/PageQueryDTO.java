package com.parking.domain.dto.common;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class PageQueryDTO {

    @Min(1)
    private long pageNo = 1;

    @Min(10)
    @Max(100)
    private long pageSize = 20;

    private String sortField = "entry_time";

    private String sortOrder = "desc";

    private boolean advanced;
}
