package com.frostnerd.smokescreen.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import com.frostnerd.dnstunnelproxy.DnsServerInformation
import com.frostnerd.networking.NetworkUtil
import com.frostnerd.smokescreen.R
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.frostnerd.encrypteddnstunnelproxy.AbstractHttpsDNSHandle
import com.frostnerd.encrypteddnstunnelproxy.HttpsDnsServerInformation
import com.frostnerd.encrypteddnstunnelproxy.ServerConfiguration
import com.frostnerd.encrypteddnstunnelproxy.createSimpleServerConfig
import com.frostnerd.smokescreen.BuildConfig
import com.frostnerd.smokescreen.activity.BackgroundVpnConfigureActivity
import com.frostnerd.smokescreen.activity.MainActivity
import com.frostnerd.smokescreen.util.Notifications
import com.frostnerd.smokescreen.getPreferences
import com.frostnerd.smokescreen.startForegroundServiceCompat
import com.frostnerd.smokescreen.util.proxy.ProxyHandler
import com.frostnerd.smokescreen.util.proxy.SmokeProxy
import com.frostnerd.vpntunnelproxy.TrafficStats
import com.frostnerd.vpntunnelproxy.VPNTunnelProxy
import java.io.Serializable
import java.lang.IllegalArgumentException


/**
 * Copyright Daniel Wolf 2018
 * All rights reserved.
 * Code may NOT be used without proper permission, neither in binary nor in source form.
 * All redistributions of this software in source code must retain this copyright header
 * All redistributions of this software in binary form must visibly inform users about usage of this software
 *
 * development@frostnerd.com
 */
class DnsVpnService : VpnService(), Runnable {
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var handle: ProxyHandler? = null
    private var dnsProxy: SmokeProxy? = null
    private var vpnProxy: VPNTunnelProxy? = null
    private var destroyed = false
    private lateinit var notificationBuilder: NotificationCompat.Builder
    private lateinit var primaryServer: ServerConfiguration
    private var secondaryServer: ServerConfiguration? = null
    private var queryCountOffset: Long = 0

    /*
        URLs passed to the Service, which haven't been retrieved from the settings.
        Null if the current servers are from the settings
     */
    private var primaryUserServerUrl: String? = null
    private var secondaryUserServerUrl: String? = null

    companion object {
        const val BROADCAST_VPN_ACTIVE = BuildConfig.APPLICATION_ID + ".VPN_ACTIVE"
        const val BROADCAST_VPN_INACTIVE = BuildConfig.APPLICATION_ID + ".VPN_INACTIVE"
        var currentTrafficStats: TrafficStats? = null
            private set

        fun startVpn(context: Context, primaryServerUrl: String? = null, secondaryServerUrl: String? = null) {
            val intent = Intent(context, DnsVpnService::class.java)
            if (primaryServerUrl != null) intent.putExtra(
                BackgroundVpnConfigureActivity.extraKeyPrimaryUrl,
                primaryServerUrl
            )
            if (secondaryServerUrl != null) intent.putExtra(
                BackgroundVpnConfigureActivity.extraKeySecondaryUrl,
                secondaryServerUrl
            )
            context.startForegroundServiceCompat(intent)
        }

        fun restartVpn(context: Context, fetchServersFromSettings: Boolean) {
            val bundle = Bundle()
            bundle.putBoolean("fetch_servers", fetchServersFromSettings)
            sendCommand(context, Command.RESTART, bundle)
        }

        fun restartVpn(context: Context, primaryServerUrl: String?, secondaryServerUrl: String?) {
            val bundle = Bundle()
            if (primaryServerUrl != null) bundle.putString(
                BackgroundVpnConfigureActivity.extraKeyPrimaryUrl,
                primaryServerUrl
            )
            if (secondaryServerUrl != null) bundle.putString(
                BackgroundVpnConfigureActivity.extraKeySecondaryUrl,
                secondaryServerUrl
            )
            sendCommand(context, Command.RESTART, bundle)
        }

        fun sendCommand(context: Context, command: Command, extras: Bundle? = null) {
            val intent = Intent(context, DnsVpnService::class.java).putExtra("command", command)
            if (extras != null) intent.putExtras(extras)
            context.startService(intent)
        }
    }


    override fun onCreate() {
        super.onCreate()
        notificationBuilder = NotificationCompat.Builder(this, Notifications.servicePersistentNotificationChannel(this))
        notificationBuilder.setContentTitle(getString(R.string.app_name))
        notificationBuilder.setSmallIcon(R.mipmap.ic_launcher_round)
        notificationBuilder.setOngoing(true)
        notificationBuilder.setAutoCancel(false)
        notificationBuilder.setSound(null)
        notificationBuilder.setUsesChronometer(true)
        notificationBuilder.setContentIntent(
            PendingIntent.getActivity(
                this, 1,
                Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        updateNotification(0)
    }

    private fun updateNotification(queryCount: Int? = null) {
        if (queryCount != null) notificationBuilder.setSubText(
            getString(
                R.string.notification_main_subtext,
                queryCount + queryCountOffset
            )
        )
        startForeground(1, notificationBuilder.build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && intent.hasExtra("command")) {
            val command = intent.getSerializableExtra("command") as Command

            when (command) {
                Command.STOP -> {
                    destroy()
                    stopForeground(true)
                    stopSelf()
                }
                Command.RESTART -> {
                    if (intent.getBooleanExtra("fetch_servers", false)) {
                        setServerConfiguration(intent)
                    }
                    setNotificationText()
                    recreateVpn()
                }
            }
        } else {
            if (!destroyed) {
                if (!this::primaryServer.isInitialized) {
                    setServerConfiguration(intent)
                    setNotificationText()
                }
                updateNotification(0)
                establishVpn()
            }
        }
        return if (destroyed) Service.START_NOT_STICKY else Service.START_STICKY
    }

    private fun setServerConfiguration(intent: Intent?) {
        if (intent != null) {
            if (intent.hasExtra(BackgroundVpnConfigureActivity.extraKeyPrimaryUrl)) {
                primaryUserServerUrl = intent.getStringExtra(BackgroundVpnConfigureActivity.extraKeyPrimaryUrl)
                primaryServer = ServerConfiguration.createSimpleServerConfig(primaryUserServerUrl!!)
            } else {
                primaryUserServerUrl = null
                primaryServer = getPreferences().primaryServerConfig
            }

            if (intent.hasExtra(BackgroundVpnConfigureActivity.extraKeySecondaryUrl)) {
                secondaryUserServerUrl = intent.getStringExtra(BackgroundVpnConfigureActivity.extraKeySecondaryUrl)
                secondaryServer = ServerConfiguration.createSimpleServerConfig(secondaryUserServerUrl!!)
            } else {
                secondaryUserServerUrl = null
                secondaryServer = getPreferences().secondaryServerConfig
            }
        } else {
            primaryServer = getPreferences().primaryServerConfig
            secondaryServer = getPreferences().secondaryServerConfig
            primaryUserServerUrl = null
            secondaryUserServerUrl = null
        }
    }

    private fun setNotificationText() {
        val text = if (secondaryServer != null) {
            getString(
                R.string.notification_main_text_with_secondary,
                primaryServer.urlCreator.baseUrl,
                secondaryServer!!.urlCreator.baseUrl,
                getPreferences().totalBypassPackageCount,
                dnsProxy?.cache?.livingCachedEntries() ?: 0
            )
        } else {
            getString(
                R.string.notification_main_text,
                primaryServer.urlCreator.baseUrl,
                getPreferences().totalBypassPackageCount,
                dnsProxy?.cache?.livingCachedEntries() ?: 0
            )
        }
        notificationBuilder.setStyle(NotificationCompat.BigTextStyle(notificationBuilder).bigText(text))
    }

    private fun establishVpn() {
        if (fileDescriptor == null) {
            fileDescriptor = createBuilder().establish()
            run()
        }
    }

    private fun recreateVpn() {
        destroy()
        destroyed = false
        establishVpn()
    }

    private fun destroy() {
        if (!destroyed) {
            queryCountOffset += currentTrafficStats?.packetsReceivedFromDevice ?: 0
            vpnProxy?.stop()
            fileDescriptor?.close()
            vpnProxy = null
            fileDescriptor = null
            destroyed = true
        }
        currentTrafficStats = null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!destroyed && resources.getBoolean(R.bool.keep_service_alive)) {
            val restartIntent = Intent(this, VpnRestartService::class.java)
            if (primaryUserServerUrl != null) restartIntent.putExtra(
                BackgroundVpnConfigureActivity.extraKeyPrimaryUrl,
                primaryUserServerUrl
            )
            if (secondaryUserServerUrl != null) restartIntent.putExtra(
                BackgroundVpnConfigureActivity.extraKeySecondaryUrl,
                secondaryUserServerUrl
            )
            startForegroundServiceCompat(restartIntent)
        }
        destroy()
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(BROADCAST_VPN_INACTIVE))
    }

    override fun onRevoke() {
        destroy()
        stopForeground(true)
        stopSelf()
        if (getPreferences().disallowOtherVpns) {
            Handler(Looper.getMainLooper()).postDelayed({
                BackgroundVpnConfigureActivity.prepareVpn(this, primaryUserServerUrl, secondaryUserServerUrl)
            }, 250)
        }
    }

    private fun createBuilder(): Builder {
        val builder = Builder()

        val dummyServerIpv4 = getPreferences().dummyDnsAddressIpv4
        val dummyServerIpv6 = getPreferences().dummyDnsAddressIpv6
        var couldSetAddress = false
        for (prefix in resources.getStringArray(R.array.interface_address_prefixes)) {
            try {
                builder.addAddress("$prefix.134", 24)
                couldSetAddress = true
            } catch (ignored: IllegalArgumentException) {
            }
        }

        if (!couldSetAddress) {
            builder.addAddress("192.168.0.10", 24)
        }
        couldSetAddress = false

        var tries = 0
        do {
            try {
                builder.addAddress(NetworkUtil.randomLocalIPv6Address(), 48)
                couldSetAddress = true
            } catch (e: IllegalArgumentException) {
                if(tries >= 5) throw e
            }
        } while(!couldSetAddress && ++tries < 5)

        if (getPreferences().catchKnownDnsServers) {
            for (server in DnsServerInformation.waitUntilKnownServersArePopulated(-1)!!.values) {
                for (ipv4Server in server.getIpv4Servers()) {
                    builder.addRoute(ipv4Server.address.address, 32)
                }
                for (ipv6Server in server.getIpv6Servers()) {
                    builder.addRoute(ipv6Server.address.address, 128)
                }
            }
        }
        builder.setSession(getString(R.string.app_name))
        builder.addDnsServer(dummyServerIpv4)
        builder.addDnsServer(dummyServerIpv6)
        builder.addRoute(dummyServerIpv4, 32)
        builder.addRoute(dummyServerIpv6, 128)
        builder.allowFamily(OsConstants.AF_INET)
        builder.allowFamily(OsConstants.AF_INET6)
        builder.setBlocking(true)

        for (defaultBypassPackage in getPreferences().bypassPackagesIterator) {
            if (isPackageInstalled(defaultBypassPackage)) {
                builder.addDisallowedApplication(defaultBypassPackage)
            }
        }
        return builder
    }

    override fun run() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            e.printStackTrace()
            prev.uncaughtException(t, e)
        }

        val list = mutableListOf<ServerConfiguration>()
        list.add(primaryServer)
        if (secondaryServer != null) list.add(secondaryServer!!)

        handle = ProxyHandler(
            list,
            connectTimeout = 500,
            queryCountCallback = {
                setNotificationText()
                updateNotification(it)
            }
        )
        dnsProxy = SmokeProxy(handle!!, this)
        vpnProxy = VPNTunnelProxy(dnsProxy!!)

        vpnProxy!!.run(fileDescriptor!!)
        currentTrafficStats = vpnProxy!!.trafficStats
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(BROADCAST_VPN_ACTIVE))
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            packageManager.getApplicationInfo(packageName, 0).enabled
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
}

fun AbstractHttpsDNSHandle.Companion.findKnownServerByUrl(url: String): HttpsDnsServerInformation? {
    for (info in AbstractHttpsDNSHandle.KNOWN_DNS_SERVERS.values) {
        for (server in info.servers) {
            if (server.address.getUrl().contains(url, true)) return info
        }
    }
    return null
}

enum class Command : Serializable {
    STOP, RESTART
}