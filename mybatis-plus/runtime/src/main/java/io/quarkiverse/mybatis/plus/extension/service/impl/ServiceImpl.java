package io.quarkiverse.mybatis.plus.extension.service.impl;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import io.quarkiverse.mybatis.plus.extension.repository.CrudRepository;
import io.quarkiverse.mybatis.plus.extension.service.IService;

public class ServiceImpl<M extends BaseMapper<T>, T> extends CrudRepository<M, T> implements IService<T> {

}
