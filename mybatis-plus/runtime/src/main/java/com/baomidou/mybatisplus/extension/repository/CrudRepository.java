package com.baomidou.mybatisplus.extension.repository;

import java.util.Collection;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.apache.ibatis.binding.MapperMethod;

import com.baomidou.mybatisplus.core.enums.SqlMethod;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.Assert;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.extension.toolkit.SqlHelper;

public abstract class CrudRepository<M extends BaseMapper<T>, T> extends AbstractRepository<M, T> {
    @Inject
    protected M baseMapper;

    public M getBaseMapper() {
        Assert.notNull(this.baseMapper, "baseMapper can not be null", new Object[0]);
        return this.baseMapper;
    }

    @Transactional
    public boolean saveBatch(Collection<T> entityList, int batchSize) {
        String sqlStatement = this.getSqlStatement(SqlMethod.INSERT_ONE);
        return this.executeBatch(entityList, batchSize, (sqlSession, entity) -> sqlSession.insert(sqlStatement, entity));
    }

    protected String getSqlStatement(SqlMethod sqlMethod) {
        return SqlHelper.getSqlStatement(this.getMapperClass(), sqlMethod);
    }

    @Transactional
    public boolean saveOrUpdateBatch(Collection<T> entityList, int batchSize) {
        TableInfo tableInfo = TableInfoHelper.getTableInfo(this.getEntityClass());
        Assert.notNull(tableInfo, "error: can not execute. because can not find cache of TableInfo for entity!", new Object[0]);
        String keyProperty = tableInfo.getKeyProperty();
        Assert.notEmpty(keyProperty, "error: can not execute. because can not find column for id from entity!", new Object[0]);
        return SqlHelper.saveOrUpdateBatch(this.getSqlSessionFactory(), this.getMapperClass(), this.log, entityList, batchSize,
                (sqlSession, entity) -> {
                    Object idVal = tableInfo.getPropertyValue(entity, keyProperty);
                    return StringUtils.checkValNull(idVal) || CollectionUtils
                            .isEmpty(sqlSession.selectList(this.getSqlStatement(SqlMethod.SELECT_BY_ID), entity));
                }, (sqlSession, entity) -> {
                    MapperMethod.ParamMap<T> param = new MapperMethod.ParamMap();
                    param.put("et", entity);
                    sqlSession.update(this.getSqlStatement(SqlMethod.UPDATE_BY_ID), param);
                });
    }

    @Transactional
    public boolean updateBatchById(Collection<T> entityList, int batchSize) {
        String sqlStatement = this.getSqlStatement(SqlMethod.UPDATE_BY_ID);
        return this.executeBatch(entityList, batchSize, (sqlSession, entity) -> {
            MapperMethod.ParamMap<T> param = new MapperMethod.ParamMap();
            param.put("et", entity);
            sqlSession.update(sqlStatement, param);
        });
    }
}
