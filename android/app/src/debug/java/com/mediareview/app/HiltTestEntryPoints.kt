package com.mediareview.app

import com.mediareview.app.feature.v2.data.V2DataModeStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * 仅 debug 变体：instrumentation 测试访问 Hilt 单例的 EntryPoint。
 *
 * 与 [HiltTestActivity] 同源（debug 源集）：EntryPoint 必须在**应用模块编译期**可见，
 * 否则生成的应用组件不会实现它（androidTest 源集里声明会 ClassCastException）。
 * Release 构建不包含本文件。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface OrganizeTestEntryPoint {
    fun dataModeStore(): V2DataModeStore
}