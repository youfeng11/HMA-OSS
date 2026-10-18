package org.frknkrc44.hma_oss.zygote.hook

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.pm.ParceledListSlice
import android.os.Binder
import android.provider.Settings
import com.v7878.unsafe.invoke.EmulatedStackFrame
import icu.nullptr.hidemyapplist.common.Constants
import icu.nullptr.hidemyapplist.common.Utils.getUserFromCallingUid
import icu.nullptr.hidemyapplist.common.settings_presets.AccessibilityPreset
import org.frknkrc44.hma_oss.zygote.service.ReturnValue
import org.frknkrc44.hma_oss.zygote.util.Logcat.logD
import org.frknkrc44.hma_oss.zygote.util.ServiceUtils
import org.frknkrc44.hma_oss.zygote.util.ZLUtils.returnType
import org.frknkrc44.hma_oss.zygote.util.ZygoteConstants.ACCESSIBILITY_SERVICE_CLASS

class AccessibilityHook : IFrameworkHook {
    override val TAG = "AccessibilityHook"

    override fun load() {
        hooker.apply {
            hookBefore(
                ACCESSIBILITY_SERVICE_CLASS,
                "getEnabledAccessibilityServiceList",
            ) { methodName, frame, returnValue ->
                val callingApps = ServiceUtils.getCallingApps(pms)
                if (callingApps.isEmpty()) return@hookBefore

                val caller = callingApps.firstOrNull { callerIsSpoofed(it) }
                if (caller != null) {
                    logD(TAG) { "@$methodName returning empty list for ${callingApps.contentToString()}" }

                    val returnedList = java.util.ArrayList<AccessibilityServiceInfo>()
                    returnValue.result = if ("Parcel" in frame.returnType.simpleName) {
                        ParceledListSlice(returnedList)
                    } else {
                        returnedList
                    }
                }
            }

            hookBefore(
                ACCESSIBILITY_SERVICE_CLASS,
                "addClient",
            ) { _, _, returnValue ->
                val callingApps = ServiceUtils.getCallingApps(pms)
                if (callingApps.isEmpty()) return@hookBefore

                val caller = callingApps.firstOrNull { callerIsSpoofed(it) }
                if (caller != null) {
                    returnValue.result = 0L
                }
            }

            hookAfter(
                ACCESSIBILITY_SERVICE_CLASS,
                "getEnabledAccessibilityServiceList",
            ) { methodName, frame, returnValue ->
                filterAccessibilityServices(methodName, frame, returnValue, true)
            }

            hookAfter(
                ACCESSIBILITY_SERVICE_CLASS,
                "getInstalledAccessibilityServiceList",
            ) { methodName, frame, returnValue ->
                filterAccessibilityServices(methodName, frame, returnValue, false)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun filterAccessibilityServices(
        methodName: String,
        frame: EmulatedStackFrame,
        returnValue: ReturnValue,
        isEnabledList: Boolean,
    ) {
        val currentResult = returnValue.result ?: return
        val callingApps = ServiceUtils.getCallingApps(pms)
        if (callingApps.isEmpty()) return

        val caller = callingApps.firstOrNull { service.isHookEnabled(it) } ?: return

        val isParceled = "Parcel" in frame.returnType.simpleName
        val rawList = if (isParceled && currentResult is ParceledListSlice<*>) {
            currentResult.list as? List<AccessibilityServiceInfo>
        } else {
            currentResult as? List<AccessibilityServiceInfo>
        }
        if (rawList.isNullOrEmpty()) return

        val callingUserId = getUserFromCallingUid(Binder.getCallingUid())

        // 消除手动服务列表与黑名单过滤的冲突
        val manualSetting = if (isEnabledList) {
            service.getSpoofedSetting(caller, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, Constants.SETTINGS_SECURE)
        } else null

        val manualComponents = mutableSetOf<ComponentName>()
        val manualStrings = mutableSetOf<String>()
        if (manualSetting != null) {
            val value = manualSetting.value
            if (!value.isNullOrEmpty()) {
                for (item in value.split(":")) {
                    val trimmed = item.trim()
                    if (trimmed.isEmpty()) continue
                    manualStrings.add(trimmed)
                    val comp = ComponentName.unflattenFromString(trimmed)
                    if (comp != null) {
                        manualComponents.add(comp)
                        manualStrings.add(comp.packageName)
                    } else if ("/" in trimmed) {
                        manualStrings.add(trimmed.substringBefore("/"))
                    }
                }
            }
        }

        val filteredList = ArrayList<AccessibilityServiceInfo>()
        var filterCount = 0

        for (info in rawList) {
            if (info == null) continue
            val si = info.resolveInfo?.serviceInfo
            val pkg = si?.packageName ?: info.id?.substringBefore("/")
            val comp = if (si?.packageName != null && si.name != null) {
                ComponentName(si.packageName, si.name)
            } else {
                info.id?.let { ComponentName.unflattenFromString(it) }
            }

            // 黑名单动态过滤
            if (pkg != null && service.shouldHide(caller, pkg, callingUserId)) {
                filterCount++
                logD(TAG) { "@$methodName filtered blacklisted service $pkg for $caller" }
                continue
            }

            // 手动指定服务列表过滤（取交集）
            if (manualSetting != null) {
                val id = info.id
                val allowed = (comp != null && comp in manualComponents) ||
                        (id != null && id in manualStrings) ||
                        (pkg != null && pkg in manualStrings)
                if (!allowed) {
                    filterCount++
                    logD(TAG) { "@$methodName filtered service $pkg (not in manual list) for $caller" }
                    continue
                }
            }

            filteredList.add(info)
        }

        if (filterCount > 0) {
            returnValue.result = if (isParceled) ParceledListSlice(filteredList) else filteredList
            service.increaseSettingsFilterCount(caller, filterCount)
            logD(TAG) { "@$methodName filtered $filterCount services for $caller, ${filteredList.size} retained" }
        }
    }

    private fun callerIsSpoofed(caller: String) =
        service.getEnabledSettingsPresets(caller).contains(AccessibilityPreset.NAME)
}
