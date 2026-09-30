package com.example.roadlog

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** APK manifest contracts; runtime recording still requires host/device checks. */
@RunWith(AndroidJUnit4::class)
class TargetSdkContractTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun sideloadApkTargetsAndroid14() {
        assertEquals(34, context.applicationInfo.targetSdkVersion)
    }

    @Test
    fun modernRecordingPermissionsAreDeclared() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val declared = info.requestedPermissions.orEmpty().toSet()
        assertTrue(declared.containsAll(listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.FOREGROUND_SERVICE_LOCATION,
            Manifest.permission.FOREGROUND_SERVICE_MICROPHONE
        )))
    }

    @Test
    fun recorderIsPrivateAndDeclaresBothForegroundTypes() {
        val info = context.packageManager.getServiceInfo(ComponentName(context, LoggerService::class.java), 0)
        assertFalse(info.exported)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            assertEquals(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                info.foregroundServiceType
            )
        }
    }
}
