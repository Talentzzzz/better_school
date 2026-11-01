package com.betterschool.domain.user.service.auth;

import com.betterschool.domain.user.adapter.repository.IUserRepository;
import com.betterschool.domain.user.model.entity.UserEntity;
import com.betterschool.domain.user.model.valobj.UserAuthResult;
import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserAuthServiceImpl implements IUserAuthService {

    private static final long CODE_TTL_SECONDS = 5 * 60L;

    private final IUserRepository userRepository;

    @Override
    public void sendCode(String phone) {
        String code = String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        userRepository.storeVerifyCode(phone, code, CODE_TTL_SECONDS);
        // 作品阶段 mock 短信通道，真实场景对接短信服务商
        log.info("[SMS-mock] 向 {} 发送验证码：{}", phone, code);
    }

    @Override
    public UserEntity register(String phone, String code, String nickname) {
        if (!userRepository.verifyVerifyCode(phone, code)) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "验证码错误或已过期");
        }
        // 保存用户 + 落注册消息 + 搜索同步消息是一个仓储原子用例（同事务写 outbox）；验证码校验（Redis）在事务外
        if (userRepository.findByPhone(phone) != null) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "该手机号已注册");
        }
        return userRepository.saveRegistered(UserEntity.create(phone, nickname));
    }

    @Override
    public UserAuthResult login(String phone, String code) {
        if (!userRepository.verifyVerifyCode(phone, code)) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "验证码错误或已过期");
        }
        UserEntity userEntity = userRepository.findByPhone(phone);
        if (userEntity == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "用户不存在，请先注册");
        }
        userEntity.setLastLoginAt(LocalDateTime.now());
        userRepository.update(userEntity);
        return new UserAuthResult(userEntity, userRepository.generateToken(userEntity.getUserId(), userEntity.getRole().name()));
    }
}
