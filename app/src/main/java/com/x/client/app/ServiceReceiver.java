/*
 ============================================================================
 Name        : ServiceReceiver.java
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2023 xyz
 Description : ServiceReceiver
 ============================================================================
 */

package com.x.client.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;

public class ServiceReceiver extends BroadcastReceiver {
	@Override
	public void onReceive(Context context, Intent intent) {
		String action = intent.getAction();
		if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
			// 不自动启动，重置为停止状态，等待用户手动启用
			Preferences prefs = new Preferences(context);
			prefs.setEnable(false);
			return;
		}
		if (TProxyService.ACTION_STATUS.equals(action)) {
			// 主进程兜底维护 Enable 状态：即使主界面不在前台也能收到
			// STATUS 广播（TProxyService 发送的显式包广播），避免状态残留。
			String status = intent.getStringExtra(TProxyService.EXTRA_STATUS);
			if (status == null) {
				return;
			}
			Preferences prefs = new Preferences(context);
			switch (status) {
				case TProxyService.STATUS_STARTED:
					prefs.setEnable(true);
					break;
				case TProxyService.STATUS_ERROR:
				case TProxyService.STATUS_STOPPED:
					prefs.setEnable(false);
					break;
				default:
					break;
			}
		}
	}
}
