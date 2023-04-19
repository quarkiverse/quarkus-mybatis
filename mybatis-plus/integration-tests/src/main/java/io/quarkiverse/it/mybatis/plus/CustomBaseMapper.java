package io.quarkiverse.it.mybatis.plus;

import java.util.Collection;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

public interface CustomBaseMapper<T> extends BaseMapper<T> {

    Integer insertBatchSomeColumn(Collection<T> entityList);
}
