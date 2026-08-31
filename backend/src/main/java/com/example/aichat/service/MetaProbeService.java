package com.example.aichat.service;

import org.springframework.stereotype.Service;

import com.baomidou.dynamic.datasource.annotation.DS;
import com.example.aichat.mapper.MetaMapper;

import lombok.RequiredArgsConstructor;

/**
 * 多数据源验证探针:
 * primaryDb() 走默认源(PRIMARY_DS 指定),devDb() 通过 @DS("dev") 强制切到 dev 源。
 * 供 /api/meta/datasource 使用,验证 dynamic-datasource 路由真实生效。
 */
@Service
@RequiredArgsConstructor
public class MetaProbeService {

    private final MetaMapper metaMapper;

    /** 默认(primary)数据源的库名 */
    public String primaryDb() {
        return metaMapper.currentSchema();
    }

    /** 强制切换到 dev 数据源查询(注解级路由) */
    @DS("dev")
    public String devDb() {
        return metaMapper.currentSchema();
    }
}
