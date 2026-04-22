SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS query_operation_log;
DROP TABLE IF EXISTS operation_log;
DROP TABLE IF EXISTS login_log;
DROP TABLE IF EXISTS sys_role_permission;
DROP TABLE IF EXISTS sys_user;
DROP TABLE IF EXISTS sys_role;
DROP TABLE IF EXISTS recognition_record;
DROP TABLE IF EXISTS parking_spot;
DROP TABLE IF EXISTS parking_record;

CREATE TABLE parking_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    plate_number VARCHAR(20) NOT NULL,
    park_no VARCHAR(20) NOT NULL,
    entry_time DATETIME NOT NULL,
    exit_time DATETIME NULL,
    duration_minutes INT NULL,
    fee DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    status VARCHAR(20) NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT chk_parking_record_status CHECK (status IN ('未出场', '已出场')),
    INDEX idx_parking_plate_entry (plate_number, entry_time),
    INDEX idx_parking_status_entry (status, entry_time),
    INDEX idx_parking_park_no (park_no),
    INDEX idx_parking_entry_time (entry_time),
    INDEX idx_parking_exit_time (exit_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE recognition_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    plate_number VARCHAR(20) NOT NULL,
    recognition_time DATETIME NOT NULL,
    accuracy DECIMAL(5,2) NOT NULL,
    recognition_type VARCHAR(20) NOT NULL,
    source_url VARCHAR(255) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_recognition_plate_time (plate_number, recognition_time),
    INDEX idx_recognition_type_time (recognition_type, recognition_time),
    INDEX idx_recognition_accuracy (accuracy)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE parking_spot (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    spot_no VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    current_plate VARCHAR(20) NULL,
    entry_time DATETIME NULL,
    record_id BIGINT NULL,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_parking_spot_no (spot_no),
    INDEX idx_spot_status (status),
    INDEX idx_spot_current_plate (current_plate),
    INDEX idx_spot_record_id (record_id),
    CONSTRAINT fk_spot_record
        FOREIGN KEY (record_id) REFERENCES parking_record(id)
        ON UPDATE CASCADE
        ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE sys_role (
    role_code VARCHAR(50) PRIMARY KEY,
    role_name VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ENABLED',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_sys_role_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE sys_user (
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

CREATE TABLE sys_role_permission (
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

CREATE TABLE query_operation_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    operator_name VARCHAR(50) NOT NULL,
    module VARCHAR(50) NOT NULL,
    query_conditions TEXT NULL,
    query_time DATETIME NOT NULL,
    cost_ms BIGINT NOT NULL,
    request_uri VARCHAR(200) NULL,
    INDEX idx_query_operator_time (operator_name, query_time),
    INDEX idx_query_time (query_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE operation_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    operator_name VARCHAR(50) NOT NULL,
    operation_content VARCHAR(255) NOT NULL,
    request_uri VARCHAR(200) NULL,
    ip VARCHAR(64) NULL,
    device VARCHAR(255) NULL,
    operation_time DATETIME NOT NULL,
    INDEX idx_operation_time (operation_time),
    INDEX idx_operation_operator (operator_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE login_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(50) NOT NULL,
    ip VARCHAR(64) NULL,
    device VARCHAR(255) NULL,
    login_status VARCHAR(20) NOT NULL,
    message VARCHAR(255) NULL,
    login_time DATETIME NOT NULL,
    INDEX idx_login_time (login_time),
    INDEX idx_login_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET FOREIGN_KEY_CHECKS = 1;
