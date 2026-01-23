package io.github.crazycoder.copysettingpath

import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.diagnostic.debug

/**
 * Handles dynamic plugin loading/unloading to ensure the [MouseEventInterceptor] is properly
 * registered when the plugin is installed or enabled without IDE restart.
 *
 * This works together with [CopySettingPathAppLifecycleListener]:
 * - [CopySettingPathAppLifecycleListener] handles registration at IDE startup
 * - [CopySettingPathDynamicPluginListener] handles registration when the plugin is loaded dynamically
 *
 * The [MouseEventInterceptor] service properly disposes itself via its [com.intellij.openapi.Disposable]
 * implementation when the plugin is unloaded, so explicit cleanup in [beforePluginUnload] is not required.
 */
class CopySettingPathDynamicPluginListener : DynamicPluginListener {

    companion object {
        private const val PLUGIN_ID = "io.github.crazycoder.copysettingpath"
    }

    override fun pluginLoaded(pluginDescriptor: IdeaPluginDescriptor) {
        if (pluginDescriptor.pluginId.idString != PLUGIN_ID) return

        LOG.debug { "Copy Setting Path plugin loaded dynamically, registering MouseEventInterceptor" }
        MouseEventInterceptor.getInstance().register()
    }

    override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
        if (pluginDescriptor.pluginId.idString != PLUGIN_ID) return

        LOG.debug { "Copy Setting Path plugin unloading (isUpdate=$isUpdate)" }
        // MouseEventInterceptor cleanup is handled automatically via Disposable
    }
}
