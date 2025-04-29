package io.quarkiverse.mybatis.plus.extension.service;

import java.util.Collection;

import jakarta.transaction.Transactional;

import com.baomidou.mybatisplus.extension.repository.IRepository;

public interface IService<T> extends IRepository<T> {

    @Transactional(rollbackOn = Exception.class)
    default boolean saveBatch(Collection<T> entityList) {
        return saveBatch(entityList, DEFAULT_BATCH_SIZE);
    }

    @Transactional(rollbackOn = Exception.class)
    default boolean saveOrUpdateBatch(Collection<T> entityList) {
        return saveOrUpdateBatch(entityList, DEFAULT_BATCH_SIZE);
    }

    @Transactional(rollbackOn = Exception.class)
    default boolean removeBatchByIds(Collection<?> list) {
        return removeByIds(list);
    }

    @Transactional(rollbackOn = Exception.class)
    default boolean updateBatchById(Collection<T> entityList) {
        return updateBatchById(entityList, DEFAULT_BATCH_SIZE);
    }
}
