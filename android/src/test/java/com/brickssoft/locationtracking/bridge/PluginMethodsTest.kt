package com.brickssoft.locationtracking.bridge

import com.brickssoft.locationtracking.LocationTrackingPlugin
import com.brickssoft.locationtracking.permission.PermissionType
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.PermissionCallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Method

/** The plugin class exposes exactly the JS contract (§1) to Capacitor. Reflection only; no bridge is created. */
class PluginMethodsTest {
    private val pluginClass = LocationTrackingPlugin::class.java

    private val pluginMethods: Map<String, Method> =
        pluginClass.methods.filter { it.isAnnotationPresent(PluginMethod::class.java) }.associateBy { it.name }

    private fun returnTypeOf(name: String): String =
        pluginMethods.getValue(name).getAnnotation(PluginMethod::class.java)!!.returnType

    @Test
    fun `every JS method is a plugin method taking a PluginCall`() {
        for (name in JS_METHODS) {
            val method = pluginMethods[name] ?: throw AssertionError("missing @PluginMethod $name")
            assertTrue(name, method.parameterTypes.contentEquals(arrayOf(PluginCall::class.java)))
        }
    }

    @Test
    fun `no plugin method outside the JS contract`() {
        assertEquals(emptySet<String>(), pluginMethods.keys - JS_METHODS - CAPACITOR_ONLY)
    }

    @Test
    fun `watchPosition is a callback method and the others return promises`() {
        assertEquals(PluginMethod.RETURN_CALLBACK, returnTypeOf("watchPosition"))
        for (name in JS_METHODS - BASE_METHODS - "watchPosition") {
            assertEquals(name, PluginMethod.RETURN_PROMISE, returnTypeOf(name))
        }
    }

    @Test
    fun `annotation name and permission aliases are fixed`() {
        val annotation = pluginClass.getAnnotation(CapacitorPlugin::class.java)!!

        assertEquals("LocationTracking", annotation.name)
        assertEquals(PermissionType.entries.map { it.alias }.toSet(), annotation.permissions.map { it.alias }.toSet())
    }

    @Test
    fun `the permission callback named by the plugin exists`() {
        val field = pluginClass.getDeclaredField("PERMISSION_CALLBACK").apply { isAccessible = true }
        val name = field.get(null) as String

        val callback = pluginClass.declaredMethods.single { it.isAnnotationPresent(PermissionCallback::class.java) }

        assertEquals(name, callback.name)
        assertTrue(callback.parameterTypes.contentEquals(arrayOf(PluginCall::class.java)))
    }

    @Test
    fun `method list matches src definitions ts`() {
        val file = File("../src/definitions.ts")
        assumeTrue("definitions.ts not found from ${File(".").absolutePath}", file.exists())
        val text = file.readText()
        val start = text.indexOf("export interface LocationTrackingPlugin {")
        assertTrue(start >= 0)
        val body = text.substring(start, text.indexOf("\n}", start))

        val names = Regex("""^ {2}(\w+)\(""", RegexOption.MULTILINE).findAll(body).map { it.groupValues[1] }.toSet()

        assertEquals(JS_METHODS, names)
    }

    companion object {
        /** Every method of the TS `LocationTrackingPlugin` interface. */
        val JS_METHODS: Set<String> = setOf(
            "ready", "setConfig", "reset", "getState", "start", "startGeofences", "stop", "changePace",
            "getCurrentPosition", "watchPosition", "clearWatch",
            "getOdometer", "setOdometer", "resetOdometer",
            "getLocations", "getCount", "insertLocation", "destroyLocations", "destroyLocation", "sync",
            "addGeofence", "addGeofences", "removeGeofence", "removeGeofences", "getGeofences", "getGeofence",
            "geofenceExists",
            "getHeartbeatStatus",
            "getProviderState", "isPowerSaveMode", "getBatteryOptimizationStatus", "openBatteryOptimizationSettings",
            "getPowerManagerInfo", "openPowerManagerSettings", "openLocationSettings", "openAppSettings",
            "getDeviceInfo", "getSensors",
            "checkPermissions", "requestPermissions",
            "log", "getLog", "destroyLog", "uploadLog", "emailLog",
            "addListener", "removeAllListeners",
        )

        /** Provided by Capacitor's `Plugin` base class. */
        val BASE_METHODS: Set<String> = setOf("addListener", "removeAllListeners")

        /** Capacitor base-class methods that the TS interface does not declare. */
        private val CAPACITOR_ONLY: Set<String> = setOf("removeListener")
    }
}
