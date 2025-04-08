package io.quarkiverse.it.mybatis.plus;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class UserServiceImpl extends ServiceImpl<UserMapper, User> {
    @Inject
    UserMapper userMapper;

    @PostConstruct
    public void init() {
        this.baseMapper = userMapper;
    }
}
