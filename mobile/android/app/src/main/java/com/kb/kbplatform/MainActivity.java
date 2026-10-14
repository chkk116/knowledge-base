/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform;

import android.content.Intent;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginHandle;

public class MainActivity extends BridgeActivity {

    /**
     * App 已在运行时，从系统分享面板再次分享进来的 intent 会走这里（launchMode=singleTask）。
     * 转发给 ShareReceiverPlugin 处理（复制文件 + 通知 Web 层跳导入页）。
     *
     * ⚠️ Capacitor 8 的 Bridge.getPlugin(id) 返回 PluginHandle（不是 Plugin），
     *    要再 .getInstance() 才是插件实例。
     */
    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        try {
            PluginHandle h = getBridge().getPlugin("ShareReceiver");
            Plugin p = h == null ? null : h.getInstance();
            if (p instanceof ShareReceiverPlugin) {
                ((ShareReceiverPlugin) p).handleIntent(intent);
            }
        } catch (Exception e) {
            /* 忽略：分享处理失败不影响 App 正常使用 */
        }
    }
}
