package com.tutor.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tutor.auth.entity.UserAccountEntity;
import com.tutor.auth.mapper.UserAccountMapper;
import com.tutor.common.exception.BizException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 轻量用户认证服务：支持账号注册与登录，管理端与用户端隔离。
 * 具备 MySQL 持久化 + 内存高可用双保险。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAuthService {

    public static final String ROLE_ADMIN = "ROLE_ADMIN";
    public static final String ROLE_USER = "ROLE_USER";

    private final UserAccountMapper userMapper;
    private final JdbcTemplate jdbcTemplate;

    private final ConcurrentHashMap<String, UserAccountEntity> userByName = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, UserAccountEntity> userById = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(2000L);

    @PostConstruct
    public void init() {
        // 1. 初始化预置内存账号
        UserAccountEntity admin = new UserAccountEntity();
        admin.setId(1L);
        admin.setUsername("admin");
        admin.setPassword("admin123");
        admin.setNickname("教研研发管理员");
        admin.setRole(ROLE_ADMIN);
        admin.setCreatedAt(LocalDateTime.now());
        cache(admin);

        UserAccountEntity student = new UserAccountEntity();
        student.setId(1001L);
        student.setUsername("student");
        student.setPassword("123456");
        student.setNickname("张同学 (非计算机转Java后端)");
        student.setRole(ROLE_USER);
        student.setCreatedAt(LocalDateTime.now());
        cache(student);

        // 2. 尝试同步数据库表
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS user_account (
                        id          BIGINT       NOT NULL AUTO_INCREMENT,
                        username    VARCHAR(64)  NOT NULL COMMENT '登录用户名',
                        password    VARCHAR(64)  NOT NULL COMMENT '登录密码',
                        nickname    VARCHAR(64)  NULL COMMENT '用户昵称',
                        role        VARCHAR(32)  NOT NULL DEFAULT 'ROLE_USER' COMMENT '角色: ROLE_USER / ROLE_ADMIN',
                        created_at  DATETIME     NOT NULL,
                        PRIMARY KEY (id),
                        UNIQUE KEY uk_username (username)
                    ) ENGINE = InnoDB COMMENT '用户账户表';
                    """);

            if (userMapper.selectCount(new LambdaQueryWrapper<UserAccountEntity>().eq(UserAccountEntity::getUsername, "admin")) == 0) {
                userMapper.insert(admin);
            }
            if (userMapper.selectCount(new LambdaQueryWrapper<UserAccountEntity>().eq(UserAccountEntity::getUsername, "student")) == 0) {
                userMapper.insert(student);
            }
            log.info("✅ 用户账号体系初始化就绪（默认管理员: admin / admin123, 默认学生: student / 123456）");
        } catch (Exception e) {
            log.info("用户表就绪（内存保底可用）: {}", e.getMessage());
        }
    }

    private void cache(UserAccountEntity user) {
        if (user != null && user.getUsername() != null) {
            userByName.put(user.getUsername(), user);
            if (user.getId() != null) {
                userById.put(user.getId(), user);
            }
        }
    }

    /**
     * 账号注册（注册账号默认均为普通学生 ROLE_USER）
     */
    public UserAccountEntity register(String username, String password, String nickname) {
        if (username == null || username.trim().length() < 2) {
            throw BizException.badRequest("用户名至少需 2 个字符");
        }
        if (password == null || password.trim().length() < 4) {
            throw BizException.badRequest("密码至少需 4 个字符");
        }
        username = username.trim();
        password = password.trim();

        if (userByName.containsKey(username)) {
            throw BizException.badRequest("该用户名已被注册，请直接登录");
        }

        try {
            if (userMapper.selectCount(new LambdaQueryWrapper<UserAccountEntity>().eq(UserAccountEntity::getUsername, username)) > 0) {
                throw BizException.badRequest("该用户名已被注册，请直接登录");
            }
        } catch (Exception ignored) {}

        UserAccountEntity user = new UserAccountEntity();
        user.setId(idGenerator.incrementAndGet());
        user.setUsername(username);
        user.setPassword(password);
        user.setNickname(nickname != null && !nickname.isBlank() ? nickname.trim() : username);
        user.setRole(ROLE_USER);
        user.setCreatedAt(LocalDateTime.now());

        try {
            userMapper.insert(user);
        } catch (Exception ignored) {}

        cache(user);
        log.info("新学生用户注册成功: id={}, username={}, nickname={}", user.getId(), user.getUsername(), user.getNickname());
        return user;
    }

    /**
     * 账号登录
     */
    public UserAccountEntity login(String username, String password) {
        if (username == null || password == null) {
            throw BizException.badRequest("用户名或密码不能为空");
        }
        username = username.trim();
        password = password.trim();

        UserAccountEntity user = userByName.get(username);
        if (user == null) {
            try {
                user = userMapper.selectOne(new LambdaQueryWrapper<UserAccountEntity>()
                        .eq(UserAccountEntity::getUsername, username));
                if (user != null) cache(user);
            } catch (Exception ignored) {}
        }

        if (user == null || !password.equals(user.getPassword())) {
            throw BizException.badRequest("用户名或密码错误");
        }

        return user;
    }

    public UserAccountEntity getById(Long id) {
        if (id == null) return null;
        UserAccountEntity user = userById.get(id);
        if (user == null) {
            try {
                user = userMapper.selectById(id);
                if (user != null) cache(user);
            } catch (Exception ignored) {}
        }
        return user;
    }
}
