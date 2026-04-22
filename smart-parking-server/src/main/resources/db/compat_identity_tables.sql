CREATE TABLE IF NOT EXISTS sys_role (
    role_code VARCHAR(50) PRIMARY KEY,
    role_name VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ENABLED',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_sys_role_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(50) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    real_name VARCHAR(50) NOT NULL,
    phone VARCHAR(20) NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ENABLED',
    role_code VARCHAR(50) NOT NULL,
    last_login_time DATETIME NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_sys_user_username (username),
    INDEX idx_sys_user_role (role_code),
    INDEX idx_sys_user_status (status),
    CONSTRAINT fk_sys_user_role
        FOREIGN KEY (role_code) REFERENCES sys_role(role_code)
        ON UPDATE CASCADE
        ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sys_role_permission (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    role_code VARCHAR(50) NOT NULL,
    permission_code VARCHAR(100) NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_sys_role_permission (role_code, permission_code),
    INDEX idx_sys_permission_code (permission_code),
    CONSTRAINT fk_sys_role_permission_role
        FOREIGN KEY (role_code) REFERENCES sys_role(role_code)
        ON UPDATE CASCADE
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO sys_role (role_code, role_name, status) VALUES
('ADMIN', 'Administrator', 'ENABLED'),
('OPERATOR', 'Operator', 'ENABLED'),
('AUDITOR', 'Auditor', 'ENABLED'),
('VIEWER', 'Viewer', 'ENABLED');

INSERT IGNORE INTO sys_user (id, username, password_hash, real_name, phone, status, role_code, last_login_time) VALUES
(1001, 'admin', 'Admin1234', 'SysAdmin', '13800000000', 'ENABLED', 'ADMIN', NOW()),
(1002, 'operator', 'Operator123', 'DutyOperator', '13900000000', 'ENABLED', 'OPERATOR', NOW()),
(1003, 'auditor', 'Auditor123', 'AuditUser', '13700000000', 'ENABLED', 'AUDITOR', NOW()),
(1004, 'viewer', 'Viewer123', 'ReadOnlyUser', '13600000000', 'ENABLED', 'VIEWER', NOW());

INSERT IGNORE INTO sys_role_permission (role_code, permission_code) VALUES
('ADMIN', 'dashboard:view'),
('ADMIN', 'dashboard:spot:detail'),
('ADMIN', 'parking:query'),
('ADMIN', 'parking:assign'),
('ADMIN', 'recognition:query'),
('ADMIN', 'recognition:image'),
('ADMIN', 'recognition:video'),
('ADMIN', 'recognition:export'),
('ADMIN', 'datacenter:query'),
('ADMIN', 'datacenter:export:excel'),
('ADMIN', 'datacenter:export:pdf'),
('ADMIN', 'admin:user:view'),
('ADMIN', 'admin:user:assign-role'),
('ADMIN', 'admin:role:view'),
('ADMIN', 'admin:role:edit'),
('ADMIN', 'admin:log:view'),
('ADMIN', 'profile:view'),
('ADMIN', 'profile:edit'),
('ADMIN', 'profile:password'),
('OPERATOR', 'dashboard:view'),
('OPERATOR', 'parking:query'),
('OPERATOR', 'parking:assign'),
('OPERATOR', 'recognition:query'),
('OPERATOR', 'recognition:image'),
('OPERATOR', 'recognition:video'),
('OPERATOR', 'recognition:export'),
('OPERATOR', 'datacenter:query'),
('OPERATOR', 'datacenter:export:excel'),
('OPERATOR', 'datacenter:export:pdf'),
('OPERATOR', 'profile:view'),
('OPERATOR', 'profile:edit'),
('OPERATOR', 'profile:password'),
('AUDITOR', 'dashboard:view'),
('AUDITOR', 'parking:query'),
('AUDITOR', 'recognition:query'),
('AUDITOR', 'datacenter:query'),
('AUDITOR', 'datacenter:export:excel'),
('AUDITOR', 'datacenter:export:pdf'),
('AUDITOR', 'profile:view'),
('VIEWER', 'dashboard:view'),
('VIEWER', 'parking:query'),
('VIEWER', 'recognition:query'),
('VIEWER', 'profile:view');
