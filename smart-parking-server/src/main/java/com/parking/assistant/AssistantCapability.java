package com.parking.assistant;

import java.util.Arrays;
import java.util.Optional;

public enum AssistantCapability {

    DASHBOARD_REALTIME("DASHBOARD_REALTIME", "实时看板概览", "dashboard:view", true, false, "查看实时看板概览，支持按今天、本周、本月查询"),

    PARKING_RECORD_QUERY("PARKING_RECORD_QUERY", "停车记录查询", "parking:query", true, false, "查询停车记录"),
    PARKING_RECORD_DETAIL("PARKING_RECORD_DETAIL", "停车记录详情", "parking:query", true, false, "查看指定停车记录详情"),
    PARKING_RECORD_UPDATE("PARKING_RECORD_UPDATE", "停车记录修改", "parking:query", false, true, "修改指定停车记录"),
    PARKING_SPOT_LIST("PARKING_SPOT_LIST", "车位状态查询", "parking:assign", true, false, "查看全部车位状态与占用情况"),
    PARKING_ASSIGNMENT_VEHICLES("PARKING_ASSIGNMENT_VEHICLES", "待分配车辆查询", "parking:assign", true, false, "查看待分配车位的车辆"),
    PARKING_SPOT_ASSIGN("PARKING_SPOT_ASSIGN", "车位分配", "parking:assign", false, true, "将指定车辆分配到车位"),
    PARKING_SPOT_STATUS_UPDATE("PARKING_SPOT_STATUS_UPDATE", "车位状态修改", "parking:assign", false, true, "修改车位状态"),

    RECOGNITION_RECORD_QUERY("RECOGNITION_RECORD_QUERY", "识别记录查询", "recognition:query", true, false, "查询识别记录"),
    RECOGNITION_RECORD_EXPORT_EXCEL("RECOGNITION_RECORD_EXPORT_EXCEL", "识别记录导出表格", "recognition:query", true, false, "导出识别记录表格"),
    RECOGNITION_VIDEO_ACCESS_GUIDE("RECOGNITION_VIDEO_ACCESS_GUIDE", "视频接入说明", "recognition:video", true, false, "查看视频流接入与识别说明"),

    DATACENTER_OVERVIEW("DATACENTER_OVERVIEW", "数据中心概览", "datacenter:query", true, false, "查看数据中心概览"),
    DATACENTER_RECORD_QUERY("DATACENTER_RECORD_QUERY", "数据中心记录查询", "datacenter:query", true, false, "查询数据中心停车记录"),
    DATACENTER_RECORD_EXPORT_EXCEL("DATACENTER_RECORD_EXPORT_EXCEL", "数据中心记录导出表格", "datacenter:export:excel", true, false, "导出数据中心停车记录表格"),
    DATACENTER_RECORD_EXPORT_PDF("DATACENTER_RECORD_EXPORT_PDF", "数据中心记录导出文档", "datacenter:export:pdf", true, false, "导出数据中心停车记录文档"),

    ADMIN_USER_PAGE("ADMIN_USER_PAGE", "用户查询", "admin:user:view", true, false, "查询用户列表"),
    ADMIN_USER_UPDATE("ADMIN_USER_UPDATE", "用户资料修改", "admin:user:assign-role", false, true, "修改指定用户资料"),
    ADMIN_ROLE_LIST("ADMIN_ROLE_LIST", "角色查询", "admin:role:view", true, false, "查看角色列表"),
    ADMIN_PERMISSION_TREE("ADMIN_PERMISSION_TREE", "权限树查询", "admin:role:view", true, false, "查看系统权限树"),
    ADMIN_OPERATION_LOGS("ADMIN_OPERATION_LOGS", "操作日志查询", "admin:log:view", true, false, "查询操作日志"),
    ADMIN_LOGIN_LOGS("ADMIN_LOGIN_LOGS", "登录日志查询", "admin:log:view", true, false, "查询登录日志"),
    ADMIN_ASSIGN_ROLE("ADMIN_ASSIGN_ROLE", "用户分配角色", "admin:user:assign-role", false, true, "给指定用户分配角色"),
    ADMIN_ROLE_PERMISSION_UPDATE("ADMIN_ROLE_PERMISSION_UPDATE", "角色权限修改", "admin:role:edit", false, true, "修改指定角色权限"),

    PROFILE_ME("PROFILE_ME", "个人资料查询", "profile:view", true, false, "查看当前账号个人资料"),
    PROFILE_LOGIN_LOGS("PROFILE_LOGIN_LOGS", "个人登录日志", "profile:view", true, false, "查看当前账号登录日志"),
    PROFILE_UPDATE("PROFILE_UPDATE", "个人资料修改", "profile:edit", false, true, "修改当前账号个人资料"),
    PROFILE_PASSWORD_CHANGE("PROFILE_PASSWORD_CHANGE", "个人密码修改", "profile:password", false, true, "修改当前账号密码");

    private final String code;
    private final String displayName;
    private final String permission;
    private final boolean readOnly;
    private final boolean confirmationRequired;
    private final String description;

    AssistantCapability(String code,
                        String displayName,
                        String permission,
                        boolean readOnly,
                        boolean confirmationRequired,
                        String description) {
        this.code = code;
        this.displayName = displayName;
        this.permission = permission;
        this.readOnly = readOnly;
        this.confirmationRequired = confirmationRequired;
        this.description = description;
    }

    public String code() {
        return code;
    }

    public String displayName() {
        return displayName;
    }

    public String permission() {
        return permission;
    }

    public boolean readOnly() {
        return readOnly;
    }

    public boolean confirmationRequired() {
        return confirmationRequired;
    }

    public String description() {
        return description;
    }

    public static Optional<AssistantCapability> fromCode(String code) {
        return Arrays.stream(values())
                .filter(item -> item.code.equalsIgnoreCase(code))
                .findFirst();
    }
}
