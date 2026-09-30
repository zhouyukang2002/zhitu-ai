package com.tutor.common.context;

/**
 * 用户上下文（替代 tj-auth-resource-sdk）：
 * 开发期由 UserContextInterceptor 从 X-User-Id 头解析并写入 ThreadLocal，
 * 未携带时兜底 userId=1（对齐《后端开发注意事项》1.3 的约定）。
 * 登录联调时前端统一加 header，后端零改动。
 */
public final class UserContext {

    private static final ThreadLocal<Long> HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<String> ROLE_HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<String> USERNAME_HOLDER = new ThreadLocal<>();

    public static final long DEFAULT_USER_ID = 1L;
    public static final String DEFAULT_ROLE = "ROLE_ADMIN";

    private UserContext() {
    }

    public static Long getUser() {
        return HOLDER.get() != null ? HOLDER.get() : DEFAULT_USER_ID;
    }

    public static Long getUserOrNull() {
        return HOLDER.get();
    }

    public static String getRole() {
        return ROLE_HOLDER.get() != null ? ROLE_HOLDER.get() : DEFAULT_ROLE;
    }

    public static String getUsername() {
        return USERNAME_HOLDER.get() != null ? USERNAME_HOLDER.get() : "user_" + getUser();
    }

    public static boolean isAdmin() {
        return ROLE_HOLDER.get() != null && "ROLE_ADMIN".equalsIgnoreCase(ROLE_HOLDER.get());
    }

    public static void setUser(Long userId) {
        HOLDER.set(userId);
    }

    public static void setUser(Long userId, String username, String role) {
        HOLDER.set(userId);
        USERNAME_HOLDER.set(username);
        ROLE_HOLDER.set(role);
    }

    public static void clear() {
        HOLDER.remove();
        ROLE_HOLDER.remove();
        USERNAME_HOLDER.remove();
    }
}
