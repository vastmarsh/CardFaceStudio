package com.cardface.studio

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.cardface.studio.databinding.ActivityMainBinding
import rikka.shizuku.Shizuku
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQ_SHIZUKU) {
            val ok = grantResult == PackageManager.PERMISSION_GRANTED
            toast(if (ok) "Shizuku 授权成功" else "Shizuku 授权被拒绝")
            refreshEngine()
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener { refreshEngine() }

    private val pickApp = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val pkg = result.data?.getStringExtra(AppListActivity.EXTRA_PKG) ?: return@registerForActivityResult
            val label = result.data?.getStringExtra(AppListActivity.EXTRA_LABEL)
            Prefs.setPkg(this, pkg)
            Prefs.setLabel(this, label)
            Prefs.setDir(this, "")
            updateTarget()
            b.tvProbe.text = "已选择应用，请点「浏览目录」定位卡面文件夹。"
            b.tvProbe.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    private val pickDir = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val dir = result.data?.getStringExtra(FolderBrowserActivity.EXTRA_SELECTED) ?: return@registerForActivityResult
            Prefs.setDir(this, dir)
            updateTarget()
            probeDir()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        ShizukuBridge.addPermissionListener(permissionListener)
        ShizukuBridge.addBinderListener(binderReceived)

        b.btnRequestShizuku.setOnClickListener { requestShizuku() }
        b.btnRefreshEngine.setOnClickListener {
            b.tvEngine.text = "执行身份：检测中…"
            refreshEngine()
        }

        b.btnPickApp.setOnClickListener {
            pickApp.launch(Intent(this, AppListActivity::class.java))
        }

        b.btnPickDir.setOnClickListener {
            val pkg = Prefs.pkg(this)
            if (pkg == null) {
                toast("请先选择钱包应用")
                return@setOnClickListener
            }
            pickDir.launch(
                Intent(this, FolderBrowserActivity::class.java)
                    .putExtra(FolderBrowserActivity.EXTRA_PKG, pkg)
            )
        }

        b.btnGallery.setOnClickListener {
            val pkg = Prefs.pkg(this)
            val dir = Prefs.dir(this)
            if (pkg == null || dir == null) {
                toast("请先选择应用与卡面目录")
                return@setOnClickListener
            }
            startActivity(
                Intent(this, GalleryActivity::class.java)
                    .putExtra(GalleryActivity.EXTRA_PKG, pkg)
                    .putExtra(GalleryActivity.EXTRA_DIR, dir)
            )
        }

        b.btnBackups.setOnClickListener {
            startActivity(Intent(this, BackupActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        updateTarget()
        refreshEngine()
    }

    override fun onDestroy() {
        super.onDestroy()
        ShizukuBridge.removePermissionListener(permissionListener)
        ShizukuBridge.removeBinderListener(binderReceived)
    }

    private fun requestShizuku() {
        if (!ShizukuBridge.binderAlive()) {
            AlertDialog.Builder(this)
                .setTitle("Shizuku 未运行")
                .setMessage(
                    "请先安装并启动 Shizuku 服务。\n\n" +
                        "• 借助电脑：adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh\n" +
                        "• 已在 Shizuku 内启动服务后，回到本页点「刷新状态」即可。\n\n" +
                        "卡面目录通常在 /sdcard/Android/data/<包名>/ 下，shell 身份即可读写。"
                )
                .setPositiveButton("知道了", null)
                .show()
            return
        }

        if (ShizukuBridge.granted()) {
            toast("已获得 Shizuku 授权")
            refreshEngine()
            return
        }

        if (!ShizukuBridge.request(REQ_SHIZUKU)) {
            toast("发起授权失败，请在 Shizuku 应用内手动授权本应用")
        }
    }

    private fun refreshEngine() {
        lifecycleScope.launch {
            val ready = withContext(Dispatchers.IO) {
                runCatching { Privilege.refresh(this@MainActivity) }.getOrDefault(false)
            }
            renderEngine(ready)
        }
    }

    private fun renderEngine(ready: Boolean) {
        val alive = ShizukuBridge.binderAlive()
        val granted = ShizukuBridge.granted()

        b.tvShizuku.text = when {
            !alive -> "Shizuku 服务：未运行（请先安装并启动 Shizuku）"
            !granted -> "Shizuku 服务：已连接，尚未授权"
            else -> "Shizuku 服务：已连接，已授权"
        }

        b.tvEngine.text = if (ready) "执行身份：${Privilege.describe()}" else "执行身份：未连接"
        b.tvEngine.setTextColor(
            ContextCompat.getColor(this, if (ready) R.color.text_ok else R.color.text_error)
        )

        b.btnRequestShizuku.isEnabled = alive && !granted
        b.btnRequestShizuku.text = if (granted) "Shizuku 已授权" else "申请 Shizuku 授权"
    }

    private fun updateTarget() {
        val pkg = Prefs.pkg(this)
        val label = Prefs.label(this)
        b.tvTargetApp.text = when {
            pkg == null -> "钱包应用：未选择"
            label.isNullOrBlank() -> "钱包应用：$pkg"
            else -> "钱包应用：$label\n$pkg"
        }
        val dir = Prefs.dir(this)
        b.tvTargetDir.text = if (dir == null) "卡面目录：未选择" else "卡面目录：$dir"
    }

    private fun probeDir() {
        val dir = Prefs.dir(this)
        if (dir == null) {
            b.tvProbe.text = ""
            return
        }
        b.tvProbe.text = "正在检测目录访问权限…"
        b.tvProbe.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { FileOps.list(dir) } }
            result.fold(
                onSuccess = { entries ->
                    val files = entries.count { !it.isDir }
                    val dirs = entries.count { it.isDir }
                    b.tvProbe.text = "目录可访问：$dirs 个子目录 / $files 个文件"
                    b.tvProbe.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_ok))
                },
                onFailure = { error ->
                    b.tvProbe.text = "无法访问：${error.message ?: "未知错误"}\n若是权限不足，请使用 root 模式运行 Shizuku。"
                    b.tvProbe.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_error))
                }
            )
        }
    }

    companion object {
        private const val REQ_SHIZUKU = 4001
    }
}
