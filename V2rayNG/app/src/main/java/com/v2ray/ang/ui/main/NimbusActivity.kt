package com.v2ray.ang.ui.main

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.util.Utils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.concurrent.thread

class NimbusActivity : ComponentActivity() {
    private lateinit var web: WebView

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == RESULT_OK) LauncherManager.startService(this) else js("nbState('stopped')")
        }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val i = intent ?: return
            when (i.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING, AppConfig.MSG_STATE_START_SUCCESS -> js("nbState('running')")
                AppConfig.MSG_STATE_NOT_RUNNING, AppConfig.MSG_STATE_STOP_SUCCESS -> js("nbState('stopped')")
                AppConfig.MSG_STATE_START_FAILURE ->
                    js("nbState('error:' + " + JSONObject.quote(i.getStringExtra("content") ?: "") + ")")
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(Bridge(), "AndroidVpn")
            loadUrl("file:///android_asset/index.html")
        }
        setContentView(web)
        ContextCompat.registerReceiver(
            this, receiver, IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY), Utils.receiverFlags()
        )
        MessageHelper.sendMsg2Service(this, AppConfig.MSG_REGISTER_CLIENT, "")
    }

    override fun onDestroy() {
        runCatching { MessageHelper.sendMsg2Service(this, AppConfig.MSG_UNREGISTER_CLIENT, "") }
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }

    private fun geoReady(): Boolean {
        val d = Utils.userAssetPath(this)
        return File(d, "geosite.dat").exists() && File(d, "geoip.dat").exists()
    }

    private fun downloadGeo(): Boolean {
        val dir = Utils.userAssetPath(this)
        for (src in listOf("Loyalsoldier/v2ray-rules-dat", "runetfreedom/russia-v2ray-rules-dat")) {
            try {
                for (f in listOf("geosite.dat", "geoip.dat")) {
                    val target = File(dir, f)
                    if (target.exists()) continue
                    val tmp = File(dir, "$f.tmp")
                    val c = URL("https://github.com/$src/releases/latest/download/$f").openConnection() as HttpURLConnection
                    c.connectTimeout = 15000
                    c.readTimeout = 30000
                    c.instanceFollowRedirects = true
                    c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                    if (tmp.length() < 1000) {
                        tmp.delete()
                        error("bad file")
                    }
                    tmp.renameTo(target)
                }
                if (geoReady()) return true
            } catch (e: Exception) {
            }
        }
        return false
    }

    private fun startVpn() {
        val i = if (SettingsManager.isVpnMode()) VpnService.prepare(this) else null
        if (i == null) LauncherManager.startService(this) else vpnPermission.launch(i)
    }

    private fun js(code: String) {
        web.post { web.evaluateJavascript(code, null) }
    }

    private fun done(cb: String, n: Int) = js("nbDone('$cb',$n)")

    private fun proto(t: String) = when (t) {
        "SHADOWSOCKS" -> "ss"
        "HYSTERIA2" -> "hy2"
        else -> t.lowercase()
    }

    inner class Bridge {
        @JavascriptInterface
        fun list(): String {
            val a = JSONArray()
            for (g in MmkvManager.decodeAllServerList()) {
                val p = MmkvManager.decodeServerConfig(g) ?: continue
                a.put(
                    JSONObject().put("id", g).put("name", p.remarks).put("host", p.server ?: "")
                        .put("port", p.serverPort ?: "").put("proto", proto(p.configType.name))
                        .put("sub", p.subscriptionId)
                )
            }
            return a.toString()
        }

        @JavascriptInterface
        fun subs(): String {
            val a = JSONArray()
            for (s in MmkvManager.decodeSubscriptions()) {
                a.put(JSONObject().put("id", s.guid).put("name", s.subscription.remarks).put("url", s.subscription.url))
            }
            return a.toString()
        }

        @JavascriptInterface
        fun selected(): String = MmkvManager.getSelectServer() ?: ""

        @JavascriptInterface
        fun select(guid: String) = MmkvManager.setSelectServer(guid)

        @JavascriptInterface
        fun removeAny(id: String) {
            if (MmkvManager.decodeSubscription(id) != null) {
                MmkvManager.removeServerViaSubid(id)
                MmkvManager.removeSubscription(id)
            } else {
                MmkvManager.removeServer(id)
            }
        }

        @JavascriptInterface
        fun addText(text: String, cb: String) {
            thread {
                val r = runCatching {
                    val (c, s) = AngConfigManager.importBatchConfig(text, AppConfig.DEFAULT_SUBSCRIPTION_ID, true)
                    c + s
                }.getOrDefault(0)
                done(cb, r)
            }
        }

        @JavascriptInterface
        fun addSub(url: String, cb: String) {
            thread {
                val n = runCatching {
                    val id = UUID.randomUUID().toString().replace("-", "")
                    val item = SubscriptionItem(remarks = runCatching { URL(url).host }.getOrDefault(url), url = url)
                    MmkvManager.encodeSubscription(id, item)
                    AngConfigManager.updateConfigViaSub(SubscriptionCache(id, item)).configCount
                }.getOrDefault(0)
                done(cb, n)
            }
        }

        @JavascriptInterface
        fun refreshSub(id: String, cb: String) {
            thread {
                val n = runCatching {
                    val s = MmkvManager.decodeSubscriptions().find { it.guid == id }
                    if (s == null) 0 else AngConfigManager.updateConfigViaSub(s).configCount
                }.getOrDefault(0)
                done(cb, n)
            }
        }

        @JavascriptInterface
        fun connect() {
            runOnUiThread {
                if (MmkvManager.getSelectServer().isNullOrEmpty()) {
                    js("nbState('stopped')")
                    return@runOnUiThread
                }
                runCatching { SettingsManager.initAssets(this@NimbusActivity, assets) }
                if (geoReady()) {
                    startVpn()
                } else {
                    js("nbState('geo')")
                    thread {
                        val ok = downloadGeo()
                        runOnUiThread {
                            if (ok) startVpn()
                            else js("nbState('error:Не удалось скачать базы geoip и geosite. Проверь интернет и попробуй снова.')")
                        }
                    }
                }
            }
        }

        @JavascriptInterface
        fun setIcon(i: Int) {
            runCatching {
                val pm = this@NimbusActivity.packageManager
                val pkg = this@NimbusActivity.packageName
                fun cn(k: Int) = ComponentName(pkg, "com.v2ray.ang.ui.main.NimbusIcon$k")
                pm.setComponentEnabledSetting(cn(i), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
                for (k in 0..5) if (k != i) {
                    pm.setComponentEnabledSetting(cn(k), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                }
            }
        }

        @JavascriptInterface
        fun disconnect() {
            runOnUiThread { LauncherManager.stopService(this@NimbusActivity) }
        }
    }
}
