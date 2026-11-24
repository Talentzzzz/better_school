package com.betterschool.usecase.user;

import com.betterschool.domain.user.adapter.repository.IUserRepository;
import com.betterschool.domain.user.model.entity.UserEntity;
import com.betterschool.domain.user.model.valobj.UserAuthResult;
import com.betterschool.domain.user.service.auth.IUserAuthService;
import com.betterschool.domain.wallet.service.IWalletAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 注册用例编排：user 域注册（域内事务：保存用户 + 落注册消息）→ wallet 域建钱包（INSERT IGNORE 幂等）；
 * 建钱包失败时由 wallet 域 getBalance 懒建兜底自愈；重试失败走"重新获取验证码 / 直接登录"
 */
@Service
@RequiredArgsConstructor
public class UserRegisterCase {

    private final IUserAuthService userAuthService;
    private final IUserRepository userRepository;
    private final IWalletAccountService walletAccountService;

    public UserAuthResult register(String phone, String code, String nickname) {
        UserEntity userEntity = userAuthService.register(phone, code, nickname);
        // 注册即送 1000 校园币（虚拟钱包演示用）
        walletAccountService.ensureWallet(userEntity.getUserId());
        return new UserAuthResult(userEntity,
                userRepository.generateToken(userEntity.getUserId(), userEntity.getRole().name()));
    }
}
