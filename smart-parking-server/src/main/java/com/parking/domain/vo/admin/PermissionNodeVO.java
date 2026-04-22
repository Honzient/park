package com.parking.domain.vo.admin;

import java.util.List;

public record PermissionNodeVO(
        String key,
        String label,
        List<PermissionNodeVO> children
) {
}
