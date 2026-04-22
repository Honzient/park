SET FOREIGN_KEY_CHECKS = 0;

TRUNCATE TABLE query_operation_log;
TRUNCATE TABLE operation_log;
TRUNCATE TABLE login_log;
TRUNCATE TABLE sys_role_permission;
TRUNCATE TABLE sys_user;
TRUNCATE TABLE sys_role;
TRUNCATE TABLE parking_spot;
TRUNCATE TABLE recognition_record;
TRUNCATE TABLE parking_record;

INSERT INTO parking_spot (spot_no, status, current_plate, entry_time, record_id) VALUES
('A-01', 'FREE', NULL, NULL, NULL),
('A-02', 'FREE', NULL, NULL, NULL),
('A-03', 'FREE', NULL, NULL, NULL),
('A-04', 'FREE', NULL, NULL, NULL),
('A-05', 'FREE', NULL, NULL, NULL),
('A-06', 'FREE', NULL, NULL, NULL),
('A-07', 'FREE', NULL, NULL, NULL),
('A-08', 'FREE', NULL, NULL, NULL),
('B-01', 'FREE', NULL, NULL, NULL),
('B-02', 'FREE', NULL, NULL, NULL),
('B-03', 'FREE', NULL, NULL, NULL),
('B-04', 'FREE', NULL, NULL, NULL),
('B-05', 'FREE', NULL, NULL, NULL),
('B-06', 'FREE', NULL, NULL, NULL),
('B-07', 'FREE', NULL, NULL, NULL),
('B-08', 'FREE', NULL, NULL, NULL),
('C-01', 'FREE', NULL, NULL, NULL),
('C-02', 'FREE', NULL, NULL, NULL),
('C-03', 'FREE', NULL, NULL, NULL),
('C-04', 'FREE', NULL, NULL, NULL),
('C-05', 'FREE', NULL, NULL, NULL),
('C-06', 'FREE', NULL, NULL, NULL),
('C-07', 'FREE', NULL, NULL, NULL),
('C-08', 'FREE', NULL, NULL, NULL),
('D-01', 'FREE', NULL, NULL, NULL),
('D-02', 'FREE', NULL, NULL, NULL),
('D-03', 'FREE', NULL, NULL, NULL),
('D-04', 'FREE', NULL, NULL, NULL),
('D-05', 'FREE', NULL, NULL, NULL),
('D-06', 'FREE', NULL, NULL, NULL),
('D-07', 'FREE', NULL, NULL, NULL),
('D-08', 'FREE', NULL, NULL, NULL),
('E-01', 'FREE', NULL, NULL, NULL),
('E-02', 'FREE', NULL, NULL, NULL),
('E-03', 'FREE', NULL, NULL, NULL),
('E-04', 'FREE', NULL, NULL, NULL),
('E-05', 'FREE', NULL, NULL, NULL),
('E-06', 'FREE', NULL, NULL, NULL),
('E-07', 'FREE', NULL, NULL, NULL),
('E-08', 'FREE', NULL, NULL, NULL),
('F-01', 'FREE', NULL, NULL, NULL),
('F-02', 'FREE', NULL, NULL, NULL),
('F-03', 'FREE', NULL, NULL, NULL),
('F-04', 'FREE', NULL, NULL, NULL),
('F-05', 'FREE', NULL, NULL, NULL),
('F-06', 'FREE', NULL, NULL, NULL),
('F-07', 'FREE', NULL, NULL, NULL),
('F-08', 'FREE', NULL, NULL, NULL);
INSERT INTO sys_role (role_code, role_name, status) VALUES
('ADMIN', 'Administrator', 'ENABLED'),
('OPERATOR', 'Operator', 'ENABLED'),
('AUDITOR', 'Auditor', 'ENABLED'),
('VIEWER', 'Viewer', 'ENABLED');

INSERT INTO sys_role_permission (role_code, permission_code) VALUES
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

INSERT INTO sys_user (id, username, password_hash, real_name, phone, status, role_code, last_login_time) VALUES
(1001, 'admin', 'Admin1234', 'SysAdmin', '13800000000', 'ENABLED', 'ADMIN', '2026-03-09 09:30:00'),
(1002, 'operator', 'Operator123', 'DutyOperator', '13900000000', 'ENABLED', 'OPERATOR', '2026-03-09 08:25:00'),
(1003, 'auditor', 'Auditor123', 'AuditUser', '13700000000', 'ENABLED', 'AUDITOR', '2026-03-09 07:45:00'),
(1004, 'viewer', 'Viewer123', 'ReadOnlyUser', '13600000000', 'ENABLED', 'VIEWER', '2026-03-09 07:10:00');

INSERT INTO login_log (username, ip, device, login_status, message, login_time) VALUES
('admin',    '127.0.0.1', 'Chrome', 'SUCCESS', 'Login success', '2026-03-09 09:30:00'),
('operator', '127.0.0.1', 'Chrome', 'SUCCESS', 'Login success', '2026-03-09 08:25:00');

INSERT INTO operation_log (operator_name, operation_content, request_uri, ip, device, operation_time) VALUES
('admin', 'System bootstrap data initialized', '/api/system/init', '127.0.0.1', 'Chrome', '2026-03-09 09:10:00'),
('admin', 'Updated role permissions', '/api/admin/roles/permissions', '127.0.0.1', 'Chrome', '2026-03-09 09:12:00');

INSERT INTO query_operation_log (operator_name, module, query_conditions, query_time, cost_ms, request_uri) VALUES
('admin', 'PARKING_RECORD_QUERY', '{"pageNo":1,"pageSize":20}', '2026-03-09 09:15:20', 45, '/api/parking/records/query'),
('admin', 'RECOGNITION_RECORD_QUERY', '{"pageNo":1,"pageSize":20}', '2026-03-09 09:16:42', 38, '/api/recognition/records/query');

SET FOREIGN_KEY_CHECKS = 1;
