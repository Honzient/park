package com.parking.repository;

import com.parking.common.exception.BusinessException;
import com.parking.security.PermissionConstants;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class InMemoryIdentityStore {

    private static final Set<String> VALID_PERMISSIONS = Set.of(
            PermissionConstants.DASHBOARD_VIEW,
            PermissionConstants.DASHBOARD_SPOT_DETAIL,
            PermissionConstants.PARKING_QUERY,
            PermissionConstants.PARKING_ASSIGN,
            PermissionConstants.RECOGNITION_QUERY,
            PermissionConstants.RECOGNITION_IMAGE,
            PermissionConstants.RECOGNITION_VIDEO,
            PermissionConstants.RECOGNITION_EXPORT,
            PermissionConstants.DATACENTER_QUERY,
            PermissionConstants.DATACENTER_EXPORT_EXCEL,
            PermissionConstants.DATACENTER_EXPORT_PDF,
            PermissionConstants.ADMIN_USER_VIEW,
            PermissionConstants.ADMIN_USER_ASSIGN_ROLE,
            PermissionConstants.ADMIN_ROLE_VIEW,
            PermissionConstants.ADMIN_ROLE_EDIT,
            PermissionConstants.ADMIN_LOG_VIEW,
            PermissionConstants.PROFILE_VIEW,
            PermissionConstants.PROFILE_EDIT,
            PermissionConstants.PROFILE_PASSWORD
    );
    private static final Map<String, Set<String>> MENU_PERMISSION_IMPLICATIONS = Map.of(
            PermissionConstants.DASHBOARD_VIEW, Set.of(
                    PermissionConstants.DASHBOARD_SPOT_DETAIL
            ),
            PermissionConstants.RECOGNITION_QUERY, Set.of(
                    PermissionConstants.RECOGNITION_EXPORT
            ),
            PermissionConstants.ADMIN_USER_VIEW, Set.of(
                    PermissionConstants.ADMIN_USER_ASSIGN_ROLE
            ),
            PermissionConstants.ADMIN_ROLE_VIEW, Set.of(
                    PermissionConstants.ADMIN_ROLE_EDIT
            ),
            PermissionConstants.PROFILE_VIEW, Set.of(
                    PermissionConstants.PROFILE_EDIT,
                    PermissionConstants.PROFILE_PASSWORD
            )
    );

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    public InMemoryIdentityStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedParameterJdbcTemplate = new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    public UserAccount getByUsername(String username) {
        if (!StringUtils.hasText(username)) {
            return null;
        }
        List<UserAccount> users = jdbcTemplate.query(
                "SELECT id, username, password_hash, real_name, phone, status, role_code, last_login_time " +
                        "FROM sys_user WHERE username = ? LIMIT 1",
                this::mapUser,
                username
        );
        return users.isEmpty() ? null : users.get(0);
    }

    public List<String> getPermissions(String roleCode) {
        if (!StringUtils.hasText(roleCode)) {
            return List.of();
        }
        List<String> permissionList = jdbcTemplate.query(
                "SELECT permission_code FROM sys_role_permission WHERE role_code = ? ORDER BY permission_code",
                (resultSet, rowNum) -> resultSet.getString("permission_code"),
                roleCode
        );
        List<String> directPermissions = permissionList.stream()
                .filter(Objects::nonNull)
                .filter(VALID_PERMISSIONS::contains)
                .distinct()
                .toList();
        return expandPermissions(directPermissions);
    }

    public List<UserAccount> listUsers() {
        return jdbcTemplate.query(
                "SELECT id, username, password_hash, real_name, phone, status, role_code, last_login_time " +
                        "FROM sys_user ORDER BY id",
                this::mapUser
        );
    }

    public List<RoleAccount> listRoles() {
        return jdbcTemplate.query(
                "SELECT role_code, role_name, status FROM sys_role ORDER BY role_code",
                (resultSet, rowNum) -> {
                    String roleCode = resultSet.getString("role_code");
                    return new RoleAccount(
                            roleCode,
                            resultSet.getString("role_name"),
                            resultSet.getString("status"),
                            new HashSet<>(getPermissions(roleCode))
                    );
                }
        );
    }

    public void assignRole(List<Long> userIds, String roleCode) {
        if (!roleExists(roleCode)) {
            throw new BusinessException(400, "Role not found");
        }
        if (userIds == null || userIds.isEmpty()) {
            return;
        }

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("roleCode", roleCode)
                .addValue("userIds", userIds);

        namedParameterJdbcTemplate.update(
                "UPDATE sys_user SET role_code = :roleCode, update_time = NOW() WHERE id IN (:userIds)",
                params
        );
    }

    public void updateUser(Long id, String username, String realName, String phone, String roleCode, String status) {
        if (!roleExists(roleCode)) {
            throw new BusinessException(400, "Role not found");
        }

        String normalizedStatus = normalizeUserStatus(status);

        try {
            int affected = jdbcTemplate.update(
                    "UPDATE sys_user SET username = ?, real_name = ?, phone = ?, role_code = ?, status = ?, update_time = NOW() WHERE id = ?",
                    username,
                    realName,
                    phone,
                    roleCode,
                    normalizedStatus,
                    id
            );
            if (affected == 0) {
                throw new BusinessException(404, "User not found");
            }
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(409, "Username already exists");
        }
    }

    @Transactional
    public void updateRolePermissions(String roleCode, List<String> permissions) {
        if (!roleExists(roleCode)) {
            throw new BusinessException(400, "Role not found");
        }

        Set<String> normalizedPermissions = sanitizePermissions(
                permissions == null ? Set.of() : new HashSet<>(permissions)
        );

        jdbcTemplate.update("DELETE FROM sys_role_permission WHERE role_code = ?", roleCode);

        if (!normalizedPermissions.isEmpty()) {
            SqlParameterSource[] batchArgs = normalizedPermissions.stream()
                    .map(permission -> new MapSqlParameterSource()
                            .addValue("roleCode", roleCode)
                            .addValue("permissionCode", permission))
                    .toArray(SqlParameterSource[]::new);

            namedParameterJdbcTemplate.batchUpdate(
                    "INSERT INTO sys_role_permission (role_code, permission_code) VALUES (:roleCode, :permissionCode)",
                    batchArgs
            );
        }

        jdbcTemplate.update("UPDATE sys_role SET update_time = NOW() WHERE role_code = ?", roleCode);
    }

    public void updateProfile(String currentUsername, String targetUsername, String realName, String phone) {
        try {
            int affected = jdbcTemplate.update(
                    "UPDATE sys_user SET username = ?, real_name = ?, phone = ?, update_time = NOW() WHERE username = ?",
                    targetUsername,
                    realName,
                    phone,
                    currentUsername
            );
            if (affected == 0) {
                throw new BusinessException(404, "User not found");
            }
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(409, "Username already exists");
        }
    }

    public void updatePassword(String username, String encodedPassword) {
        int affected = jdbcTemplate.update(
                "UPDATE sys_user SET password_hash = ?, update_time = NOW() WHERE username = ?",
                encodedPassword,
                username
        );
        if (affected == 0) {
            throw new BusinessException(404, "User not found");
        }
    }

    public void updateLastLogin(String username, LocalDateTime time) {
        jdbcTemplate.update(
                "UPDATE sys_user SET last_login_time = ?, update_time = NOW() WHERE username = ?",
                time,
                username
        );
    }

    private String normalizeUserStatus(String status) {
        if (!StringUtils.hasText(status)) {
            throw new BusinessException(400, "User status is required");
        }
        String normalized = status.trim().toUpperCase();
        if (!"ENABLED".equals(normalized) && !"DISABLED".equals(normalized)) {
            throw new BusinessException(400, "Invalid user status");
        }
        return normalized;
    }

    private boolean roleExists(String roleCode) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM sys_role WHERE role_code = ?",
                Integer.class,
                roleCode
        );
        return count != null && count > 0;
    }

    private Set<String> sanitizePermissions(Set<String> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return Set.of();
        }
        return permissions.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(StringUtils::hasText)
                .filter(VALID_PERMISSIONS::contains)
                .collect(Collectors.toCollection(HashSet::new));
    }

    private List<String> expandPermissions(List<String> directPermissions) {
        if (directPermissions == null || directPermissions.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> expanded = new LinkedHashSet<>(directPermissions);
        boolean changed;
        do {
            changed = false;
            for (Map.Entry<String, Set<String>> entry : MENU_PERMISSION_IMPLICATIONS.entrySet()) {
                if (expanded.contains(entry.getKey())) {
                    changed = expanded.addAll(entry.getValue()) || changed;
                }
            }
        } while (changed);
        return expanded.stream()
                .filter(VALID_PERMISSIONS::contains)
                .toList();
    }

    private UserAccount mapUser(ResultSet resultSet, int rowNum) throws SQLException {
        return new UserAccount(
                resultSet.getLong("id"),
                resultSet.getString("username"),
                resultSet.getString("password_hash"),
                resultSet.getString("real_name"),
                resultSet.getString("phone"),
                resultSet.getString("status"),
                resultSet.getString("role_code"),
                resultSet.getTimestamp("last_login_time") == null
                        ? null
                        : resultSet.getTimestamp("last_login_time").toLocalDateTime()
        );
    }

    public static final class UserAccount {
        private final Long id;
        private final String username;
        private String passwordHash;
        private String realName;
        private String phone;
        private String status;
        private String roleCode;
        private LocalDateTime lastLoginTime;

        public UserAccount(Long id, String username, String passwordHash, String realName, String phone,
                           String status, String roleCode, LocalDateTime lastLoginTime) {
            this.id = id;
            this.username = username;
            this.passwordHash = passwordHash;
            this.realName = realName;
            this.phone = phone;
            this.status = status;
            this.roleCode = roleCode;
            this.lastLoginTime = lastLoginTime;
        }

        public Long id() {
            return id;
        }

        public String username() {
            return username;
        }

        public String passwordHash() {
            return passwordHash;
        }

        public String realName() {
            return realName;
        }

        public String phone() {
            return phone;
        }

        public String status() {
            return status;
        }

        public String roleCode() {
            return roleCode;
        }

        public LocalDateTime lastLoginTime() {
            return lastLoginTime;
        }

        public void setPasswordHash(String passwordHash) {
            this.passwordHash = passwordHash;
        }

        public void setRealName(String realName) {
            this.realName = realName;
        }

        public void setPhone(String phone) {
            this.phone = phone;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public void setRoleCode(String roleCode) {
            this.roleCode = roleCode;
        }

        public void setLastLoginTime(LocalDateTime lastLoginTime) {
            this.lastLoginTime = lastLoginTime;
        }
    }

    public static final class RoleAccount {
        private final String roleCode;
        private final String roleName;
        private String status;
        private Set<String> permissions;

        public RoleAccount(String roleCode, String roleName, String status, Set<String> permissions) {
            this.roleCode = roleCode;
            this.roleName = roleName;
            this.status = status;
            this.permissions = permissions;
        }

        public String roleCode() {
            return roleCode;
        }

        public String roleName() {
            return roleName;
        }

        public String status() {
            return status;
        }

        public Set<String> permissions() {
            return permissions;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public void setPermissions(Set<String> permissions) {
            this.permissions = permissions;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (object == null || getClass() != object.getClass()) {
                return false;
            }
            RoleAccount that = (RoleAccount) object;
            return Objects.equals(roleCode, that.roleCode);
        }

        @Override
        public int hashCode() {
            return Objects.hash(roleCode);
        }
    }
}
