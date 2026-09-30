package com.tutor.api;

import com.tutor.auth.UserAuthService;
import com.tutor.auth.entity.UserAccountEntity;
import com.tutor.common.context.UserContext;
import com.tutor.common.web.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 账号注册与登录控制器。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserAuthService authService;

    /**
     * 获取当前登录态
     */
    @GetMapping("/current")
    public Result<Map<String, Object>> current() {
        Long userId = UserContext.getUser();
        UserAccountEntity user = authService.getById(userId);
        String username = user != null ? user.getUsername() : UserContext.getUsername();
        String nickname = user != null ? (user.getNickname() != null ? user.getNickname() : user.getUsername()) : username;
        String role = user != null ? user.getRole() : UserContext.getRole();
        boolean isAdmin = UserAuthService.ROLE_ADMIN.equalsIgnoreCase(role);

        return Result.ok(Map.of(
                "userId", userId,
                "username", username,
                "nickname", nickname,
                "role", role,
                "isAdmin", isAdmin
        ));
    }

    /**
     * 学生用户注册接口（默认角色 ROLE_USER）
     */
    @PostMapping("/register")
    public Result<Map<String, Object>> register(@RequestBody Map<String, String> body) {
        String username = body.getOrDefault("username", "");
        String password = body.getOrDefault("password", "");
        String nickname = body.getOrDefault("nickname", "");

        UserAccountEntity user = authService.register(username, password, nickname);

        return Result.ok(Map.of(
                "userId", user.getId(),
                "username", user.getUsername(),
                "nickname", user.getNickname(),
                "role", user.getRole(),
                "isAdmin", false
        ));
    }

    /**
     * 账号登录接口
     */
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        String username = body.getOrDefault("username", "");
        String password = body.getOrDefault("password", "");

        UserAccountEntity user = authService.login(username, password);
        boolean isAdmin = UserAuthService.ROLE_ADMIN.equalsIgnoreCase(user.getRole());

        return Result.ok(Map.of(
                "userId", user.getId(),
                "username", user.getUsername(),
                "nickname", user.getNickname() != null ? user.getNickname() : user.getUsername(),
                "role", user.getRole(),
                "isAdmin", isAdmin
        ));
    }
}
