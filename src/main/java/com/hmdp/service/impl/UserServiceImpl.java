package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Override
    public Result sendCode(String phone, HttpSession session) {
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机格式错误");
        }
        String code = RandomUtil.randomNumbers(6);
        //发送验证码
        log.info("发送验证码成功，验证码为：{}", code);
        session.setAttribute("code", code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        String code = loginForm.getCode();

        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机格式错误");
        }

        if (!session.getAttribute("code").equals(code)) {
            //3.不一致，报错
            return Result.fail("验证码错误");
        }

        User user = query().eq("phone", phone).one();

        if (user == null) {
            user = createUserByPhone(phone);
        }

        session.setAttribute("user",user);

        return Result.ok();
    }

    private User createUserByPhone(String phone) {
        // 快捷生成用户
        User user = new User();
        // 随机生成昵称
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        // 初始化手机号
        user.setPhone(phone);
        // 保存到数据库
        save(user);
        // 返回用户
        return user;
    }
}