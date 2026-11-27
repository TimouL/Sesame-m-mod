package io.github.aw1y2z.sesame.data;

/**
 * 全局配置字段标记：值保存在公共配置文件里，不跟随当前账号的 config_v2。
 *
 * <p>实现该接口的字段会被 {@link ConfigV2#setModelFieldsMap} 排除在 modelFieldsMap 之外，
 * 因此不会随账号配置一起序列化，也不会在加载某个账号的历史配置时被旧值回灌。
 *
 * <p>界面改动统一走 {@link #applyGlobalValue(Object)}：该方法把新值写进公共配置文件，
 * 而不是账号配置。字段内部的 {@code setObjectValue} 只更新内存副本，不落盘——
 * 否则模型初始化时构造字段就会把默认值写进配置文件，覆盖用户的设置。
 */
public interface GlobalConfigField {

    /** 界面修改时调用：把新值写入公共配置文件 */
    void applyGlobalValue(Object value);
}
