package com.sports.service;

import com.sports.dto.LoginRequest;
import com.sports.dto.LoginResponse;
import com.sports.entity.User;
import com.sports.repository.UserRepository;
import com.sports.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    /**
     * 用户登录
     */
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new BadCredentialsException("用户名或密码错误"));

        if (!"active".equals(user.getStatus())) {
            throw new RuntimeException("账号已被禁用，请联系管理员");
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BadCredentialsException("用户名或密码错误");
        }

        // 更新最后登录时间
        user.setLastLogin(LocalDateTime.now());
        userRepository.save(user);

        // 生成JWT
        String accessToken = jwtUtil.generateAccessToken(user.getId(), user.getUsername(), user.getRole());
        String refreshToken = jwtUtil.generateRefreshToken(user.getId(), user.getUsername());

        LoginResponse.UserInfo userInfo = LoginResponse.UserInfo.builder()
                .id(user.getId())
                .username(user.getUsername())
                .name(user.getName())
                .role(user.getRole())
                .avatar(user.getAvatar())
                .permissions(buildPermissions(user.getRole()))
                .build();

        log.info("用户 {} 登录成功", user.getUsername());
        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .expiresIn(86400000L)  // 24小时
                .user(userInfo)
                .build();
    }

    /**
     * 刷新令牌
     */
    public LoginResponse refreshToken(String refreshToken) {
        if (!jwtUtil.validateToken(refreshToken)) {
            throw new RuntimeException("刷新令牌无效或已过期");
        }

        Long userId = jwtUtil.extractUserId(refreshToken);
        String username = jwtUtil.extractUsername(refreshToken);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        if (!"active".equals(user.getStatus())) {
            throw new RuntimeException("账号已被禁用");
        }

        String newAccessToken = jwtUtil.generateAccessToken(user.getId(), user.getUsername(), user.getRole());
        String newRefreshToken = jwtUtil.generateRefreshToken(user.getId(), user.getUsername());

        LoginResponse.UserInfo userInfo = LoginResponse.UserInfo.builder()
                .id(user.getId())
                .username(user.getUsername())
                .name(user.getName())
                .role(user.getRole())
                .avatar(user.getAvatar())
                .permissions(buildPermissions(user.getRole()))
                .build();

        log.info("用户 {} 刷新令牌成功", username);
        return LoginResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .expiresIn(86400000L)
                .user(userInfo)
                .build();
    }

    /**
     * 修改密码
     */
    public void changePassword(Long userId, String oldPwd, String newPwd) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        if (!passwordEncoder.matches(oldPwd, user.getPassword())) {
            throw new RuntimeException("原密码错误");
        }

        if (newPwd.length() < 6) {
            throw new RuntimeException("新密码长度不能少于6位");
        }

        user.setPassword(passwordEncoder.encode(newPwd));
        userRepository.save(user);
        log.info("用户 {} 修改密码成功", user.getUsername());
    }

    /**
     * 获取用户信息
     */
    @Transactional(readOnly = true)
    public LoginResponse.UserInfo getProfile(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        return LoginResponse.UserInfo.builder()
                .id(user.getId())
                .username(user.getUsername())
                .name(user.getName())
                .role(user.getRole())
                .avatar(user.getAvatar())
                .permissions(buildPermissions(user.getRole()))
                .build();
    }

    /**
     * 更新个人信息
     */
    public LoginResponse.UserInfo updateProfile(Long userId, Map<String, Object> profile) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        if (profile.containsKey("name")) user.setName((String) profile.get("name"));
        if (profile.containsKey("phone")) user.setPhone((String) profile.get("phone"));
        if (profile.containsKey("avatar")) user.setAvatar((String) profile.get("avatar"));

        userRepository.save(user);
        log.info("用户 {} 更新个人信息成功", user.getUsername());

        return getProfile(userId);
    }

    /** Controller 别名: refresh */
    public LoginResponse refresh(String refreshToken) {
        return refreshToken(refreshToken);
    }

    /** 退出登录 */
    public void logout(String token) {
        log.info("用户退出登录");
    }

    /**
     * 根据角色构建权限列表
     */
    private List<String> buildPermissions(String role) {
        return switch (role) {
            case "ROLE_SUPER_ADMIN" -> List.of("user:manage", "class:manage", "athlete:manage",
                    "event:manage", "registration:manage", "arrange:manage",
                    "result:manage", "ranking:view", "statistics:view", "excel:manage", "system:manage");
            case "ROLE_TEACHER" -> List.of("class:manage", "athlete:manage", "event:manage",
                    "registration:manage", "arrange:manage", "result:manage",
                    "ranking:view", "statistics:view", "excel:manage");
            case "ROLE_CLASS_TEACHER" -> List.of("athlete:view", "class:view", "registration:manage",
                    "event:view", "arrange:view", "result:view", "ranking:view", "statistics:view");
            case "ROLE_STUDENT" -> List.of("event:view", "arrange:view", "result:view", "ranking:view");
            default -> List.of("event:view", "arrange:view", "result:view", "ranking:view");
        };
    }
}
