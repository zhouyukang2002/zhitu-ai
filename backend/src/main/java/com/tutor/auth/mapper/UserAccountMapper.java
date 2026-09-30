package com.tutor.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tutor.auth.entity.UserAccountEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserAccountMapper extends BaseMapper<UserAccountEntity> {
}
