/*
 ============================================================================
 Name        : ProtocolConfigBinder.java
 Description : Contract for Protocol-specific Form Binding & Persistence
 ============================================================================
 */

package com.x.client.app;

import android.view.View;

/**
 * 协议专有参数表单绑定与数据存取契约接口。
 * 解耦 ProfileEditActivity 内部庞大的 if-else 分支与控件直接操作。
 */
public interface ProtocolConfigBinder {

    /**
     * 绑定布局中的视图与事件监听。
     *
     * @param root 包含协议字段的根视图
     */
    void bindViews(View root);

    /**
     * 从持久化配置读取数据并填充到表单字段。
     *
     * @param prefs 当前配置的作用域偏好存储
     */
    void loadFromPreferences(Preferences prefs);

    /**
     * 将从 URI 导入解析得出的配置参数应用到表单。
     *
     * @param parsed 解析出的统一节点结构体
     */
    void applyImport(ProfileUriCodec.ParsedProfile parsed);

    /**
     * 校验当前协议输入字段，有非法项时显示内联错误提示；校验通过后写入 Preferences。
     *
     * @param prefs 当前配置的作用域偏好存储
     * @param saveRelay 是否允许持久化中转/接入节点参数（在独立模式或导入带地址时生效）
     * @return 校验并保存成功返回 true，存在非法输入返回 false
     */
    boolean validateAndSave(Preferences prefs, boolean saveRelay);

    /**
     * 设置全表单交互启用状态（VPN 运行期间锁定当前激活配置）。
     *
     * @param enabled 是否允许交互
     */
    void setEnabled(boolean enabled);

    /**
     * 根据优选中转模式更新中转地址/节点输入框的启用状态。
     *
     * @param independent 是否为独立中转模式
     */
    void setRelayEnabled(boolean independent);

    /**
     * 清空当前协议所有输入容器的内联错误信息。
     */
    void clearErrors();

    /**
     * 控制该协议专属参数容器的显隐。
     *
     * @param visible 是否可见
     */
    void setVisible(boolean visible);
}
