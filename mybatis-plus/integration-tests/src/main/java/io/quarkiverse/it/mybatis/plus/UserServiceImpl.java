package io.quarkiverse.it.mybatis.plus;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

/**
 * @author defned
 * @date 2025/4/2 14:55
 */
@ApplicationScoped
public class UserServiceImpl extends ServiceImpl<UserMapper, User> {
    @Inject
    UserMapper userMapper;

    @PostConstruct
    public void init() {
        this.baseMapper = userMapper;
    }
}
