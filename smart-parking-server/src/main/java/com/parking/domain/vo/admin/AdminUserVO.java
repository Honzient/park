package com.parking.domain.vo.admin;

public record AdminUserVO(
        Long id,
        String username,
        String roleCode,
        String status,
        String lastLoginTime,
        String realName,
        String phone
) {
}
