package io.quarkiverse.it.mybatis.plus;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkiverse.mybatis.plus.extension.service.impl.ServiceImpl;

@ApplicationScoped
public class UserServiceImpl extends ServiceImpl<UserMapper, User> {
}
