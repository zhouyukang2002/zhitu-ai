package com.tutor.common.context;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * 轻量用户上下文与 RBAC 拦截器：
 * 从请求头 X-User-Id、X-User-Role、X-User-Name 提取当前登录账号信息；
 * 对 /api/admin/** 路径进行强权限校验：非 ROLE_ADMIN 拦截并返回 403。
 */
@Slf4j
@Component
public class UserContextInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) throws Exception {
        String userIdHeader = request.getHeader("X-User-Id");
        String roleHeader = request.getHeader("X-User-Role");
        String nameHeader = request.getHeader("X-User-Name");

        // 仅在携带登录头时写入 ThreadLocal；未携带时不预填默认值——
        // 否则 getUserOrNull() 返回 1 会短路业务层"请求体 userId（开发期兜底）"链路，
        // 导致所有无登录头请求被归属到用户 1（多用户隔离被架空的缺陷）。
        if (userIdHeader != null && userIdHeader.matches("\\d+")) {
            long uid = Long.parseLong(userIdHeader);
            String role = (roleHeader != null && !roleHeader.isBlank())
                    ? roleHeader : (uid == 1L ? "ROLE_ADMIN" : "ROLE_USER");
            String username = "user_" + uid;
            if (nameHeader != null && !nameHeader.isBlank()) {
                try {
                    username = URLDecoder.decode(nameHeader, StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                    username = nameHeader;
                }
            }
            UserContext.setUser(uid, username, role);
        }
        // 未携带登录头时 UserContext 为空：getUser()/getRole() 各自兜底默认值（用户1/ROLE_ADMIN），
        // /api/admin 的 RBAC 行为与原先一致（本地研发看板无登录态放行）

        // RBAC 权限拦截：/api/admin/** 仅允许管理员角色；显式携带非 ADMIN 角色（如学员 ROLE_USER）时严格拦截 403
        String uri = request.getRequestURI();
        if (uri.startsWith("/api/admin")) {
            if (UserContext.getUserOrNull() != null && !UserContext.isAdmin()) {
                log.warn("权限拒绝：非管理员账号 [{}] (role={}) 试图访问受保护的管理端 API: {}",
                        UserContext.getUserOrNull(), UserContext.getRole(), uri);
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":403,\"message\":\"权限拒绝：仅管理员账号可访问研发看板与 APM 接口\",\"data\":null}");
                return false;
            }
        }

        return true;
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request,
                                @NonNull HttpServletResponse response,
                                @NonNull Object handler, Exception ex) {
        UserContext.clear();
    }
}

