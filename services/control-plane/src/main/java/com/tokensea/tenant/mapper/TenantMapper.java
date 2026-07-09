package com.tokensea.tenant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tokensea.tenant.entity.Tenant;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TenantMapper extends BaseMapper<Tenant> {}
