package com.example.aichat.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 元信息查询(多数据源验证用)。
 * SCHEMA() 在 MySQL 等价 DATABASE(),H2 也支持,双库兼容。
 */
@Mapper
public interface MetaMapper {

    /** 当前连接的库名/模式名 */
    @Select("SELECT SCHEMA()")
    String currentSchema();
}
