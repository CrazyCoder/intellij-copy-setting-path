package com.intellij.plugin.CopySettingPath

import com.intellij.ide.AppLifecycleListener

/**
 * Registers the MouseEventInterceptor when the application frame is created.
 *
 * Using AppLifecycleListener.appFrameCreated ensures the interceptor is registered
 * early enough to intercept the first Ctrl/Cmd+Click, before any dialogs can be opened.
 */
class CopySettingPathAppLifecycleListener : AppLifecycleListener {

    override fun appFrameCreated(commandLineArgs: List<String>) {
        MouseEventInterceptor.getInstance().register()
    }
}
