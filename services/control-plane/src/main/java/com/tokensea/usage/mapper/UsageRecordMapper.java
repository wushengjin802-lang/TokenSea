package com.tokensea.usage.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tokensea.usage.entity.UsageRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import java.util.Map;

@Mapper
public interface UsageRecordMapper extends BaseMapper<UsageRecord> {
    @Select("select coalesce(sum(total_tokens),0) as tokens, count(*) filter (where status <> 'SUCCESS') as errors from usage_record")
    Map<String, Object> aggregateStats();
}
