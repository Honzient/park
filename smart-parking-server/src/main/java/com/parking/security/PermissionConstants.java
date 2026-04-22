package com.parking.security;

public final class PermissionConstants {

    private PermissionConstants() {
    }

    public static final String DASHBOARD_VIEW = "dashboard:view";
    public static final String DASHBOARD_SPOT_DETAIL = "dashboard:spot:detail";

    public static final String PARKING_QUERY = "parking:query";
    public static final String PARKING_ASSIGN = "parking:assign";

    public static final String RECOGNITION_QUERY = "recognition:query";
    public static final String RECOGNITION_IMAGE = "recognition:image";
    public static final String RECOGNITION_VIDEO = "recognition:video";
    public static final String RECOGNITION_EXPORT = "recognition:export";

    public static final String DATACENTER_QUERY = "datacenter:query";
    public static final String DATACENTER_EXPORT_EXCEL = "datacenter:export:excel";
    public static final String DATACENTER_EXPORT_PDF = "datacenter:export:pdf";

    public static final String QUERY_ADVANCED = "query:advanced";

    public static final String ADMIN_USER_VIEW = "admin:user:view";
    public static final String ADMIN_USER_ASSIGN_ROLE = "admin:user:assign-role";
    public static final String ADMIN_ROLE_VIEW = "admin:role:view";
    public static final String ADMIN_ROLE_EDIT = "admin:role:edit";
    public static final String ADMIN_LOG_VIEW = "admin:log:view";

    public static final String PROFILE_VIEW = "profile:view";
    public static final String PROFILE_EDIT = "profile:edit";
    public static final String PROFILE_PASSWORD = "profile:password";
}
