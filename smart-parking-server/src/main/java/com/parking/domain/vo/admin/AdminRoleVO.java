package com.parking.domain.vo.admin;

import java.util.List;

public record AdminRoleVO(
        String roleCode,
        String roleName,
        String status,
        List<String> permissions
) {
}
