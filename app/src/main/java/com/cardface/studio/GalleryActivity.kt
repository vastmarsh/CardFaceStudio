package com.cardface.studio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cardface.studio.databinding.ActivityGalleryBinding
import com.cardface.studio.databinding.DialogCardBinding
import com.cardface.studio.databinding.ItemCardBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GalleryActivity : AppCompatActivity() {

    private lateinit var b: ActivityGalleryBinding
    private var pkg: String = ""
    private var dir: String = ""

    private val cards = ArrayList<FileEntry>()
    private lateinit var adapter: CardAdapter

    private var pendingName: String? = null

    private val memoryCache = object : LruCache<String, Bitmap>(cacheSizeKb()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val name = pendingName
        pendingName = null
        if (uri == null || name == null) return@registerForActivityResult
        handlePickedImage(name, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pkg = intent.getStringExtra(EXTRA_PKG).orEmpty()
        dir = intent.getStringExtra(EXTRA_DIR).orEmpty()
        if (pkg.isBlank() || dir.isBlank()) {
            toast("缺少应用或目录参数")
            finish()
            return
        }

        b = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.tvTitle.text = "${Prefs.label(this) ?: pkg} · 卡面库"
        b.tvPath.text = dir

        adapter = CardAdapter()
        b.rvCards.layoutManager = GridLayoutManager(this, 3)
        b.rvCards.adapter = adapter
        b.rvCards.itemAnimator = null

        b.btnReload.setOnClickListener {
            memoryCache.evictAll()
            loadCards()
        }
        b.btnBackupAll.setOnClickListener { backupAll() }

        loadCards()
    }

    override fun onDestroy() {
        super.onDestroy()
        memoryCache.evictAll()
    }

    private fun loadCards() {
        b.progress.visibility = View.VISIBLE
        b.tvEmpty.visibility = View.GONE

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { CardEngine.listCards(dir) } }
            b.progress.visibility = View.GONE

            result.fold(
                onSuccess = { list ->
                    cards.clear()
                    cards.addAll(list)
                    adapter.notifyDataSetChanged()
                    b.tvCount.text = "共 ${list.size} 张卡面"
                    if (list.isEmpty()) {
                        b.tvEmpty.visibility = View.VISIBLE
                        b.tvEmpty.text = "该目录下没有图片文件，请返回检查目录路径。"
                    }
                },
                onFailure = { error ->
                    cards.clear()
                    adapter.notifyDataSetChanged()
                    b.tvCount.text = "共 0 张卡面"
                    b.tvEmpty.visibility = View.VISIBLE
                    b.tvEmpty.text = "读取目录失败：\n${error.message ?: "未知错误"}"
                }
            )
        }
    }

    private fun loadBitmap(path: String, reqWidth: Int): Bitmap? {
        return try {
            val bytes = FileOps.readBytes(path)
            decodeThumb(bytes, reqWidth)
        } catch (_: Throwable) {
            null
        }
    }

    private fun decodeThumb(bytes: ByteArray, reqWidth: Int): Bitmap? {
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, probe)
        val width = probe.outWidth
        var sample = 1
        if (width > 0) {
            while (width / (sample * 2) >= reqWidth) sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (_: Throwable) {
            null
        }
    }

    private fun showCard(name: String) {
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = FileOps.readBytes(FileOps.join(dir, name))
                    Triple(bytes, decodeThumb(bytes, 900), ImageCodec.sniff(bytes))
                }.getOrNull()
            }
            if (info == null || info.second == null) {
                toast("无法读取该卡面文件")
                return@launch
            }

            val view = DialogCardBinding.inflate(layoutInflater)
            view.ivPreview.setImageBitmap(info.second)
            val bounds = ImageCodec.bounds(info.first)
            view.tvInfo.text = buildString {
                append(name).append("\n")
                append("体积 ").append(formatSize(info.first.size.toLong()))
                append(" · 格式 ").append(info.third.name)
                if (bounds != null) append(" · 原始尺寸 ").append(bounds.first).append("×").append(bounds.second)
                append("\n替换时自动按原尺寸、原格式写入，文件名保持不变。")
            }

            val dialog = AlertDialog.Builder(this@GalleryActivity).setView(view.root).create()
            view.btnBackupOne.setOnClickListener {
                dialog.dismiss()
                backupOne(name)
            }
            view.btnReplace.setOnClickListener {
                dialog.dismiss()
                pendingName = name
                pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            dialog.show()
        }
    }

    private fun backupOne(name: String) {
        val progress = showProgress(this, "正在备份", "保存原卡面…").also { it.show() }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { CardEngine.backupOne(this@GalleryActivity, pkg, dir, name) }
            }
            progress.dismiss()
            result.fold(
                onSuccess = { toastLong("已备份：${it.fileName}（${it.id}）") },
                onFailure = { toastLong("备份失败：${it.message}") }
            )
        }
    }

    private fun backupAll() {
        if (cards.isEmpty()) {
            toast("当前没有可备份的卡面")
            return
        }
        val progress = showProgress(this, "正在备份", "共 ${cards.size} 张卡面…").also { it.show() }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                var ok = 0
                var failed = 0
                for (card in cards.toList()) {
                    val r = runCatching { CardEngine.backupOne(this@GalleryActivity, pkg, dir, card.name) }
                    if (r.isSuccess) ok++ else failed++
                }
                ok to failed
            }
            progress.dismiss()
            AlertDialog.Builder(this@GalleryActivity)
                .setTitle("备份完成")
                .setMessage("成功 $result.first 张，失败 $result.second 张。\n备份可在「备份与复原中心」查看。")
                .setPositiveButton("好", null)
                .show()
        }
    }

    private fun handlePickedImage(name: String, uri: Uri) {
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            if (bytes == null || bytes.isEmpty()) {
                toast("读取所选图片失败，请换一张")
                return@launch
            }
            doReplace(name, bytes)
        }
    }

    private fun doReplace(name: String, newImage: ByteArray) {
        val progress = showProgress(this, "正在替换卡面", "备份原图 → 生成新图 → 写回文件…").also { it.show() }

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { CardEngine.replace(this@GalleryActivity, pkg, dir, name, newImage) }
            }
            progress.dismiss()

            memoryCache.remove(name)
            val index = cards.indexOfFirst { it.name == name }
            if (index >= 0) adapter.notifyItemChanged(index)

            result.fold(
                onSuccess = { showReplaceResult(name, it) },
                onFailure = { error ->
                    AlertDialog.Builder(this@GalleryActivity)
                        .setTitle("替换失败")
                        .setMessage((error.message ?: "未知错误") + "\n\n原卡面未被破坏，可稍后重试。")
                        .setPositiveButton("知道了", null)
                        .show()
                }
            )
        }
    }

    private fun showReplaceResult(name: String, result: ReplaceResult) {
        val message = buildString {
            append("已替换：").append(name).append("\n")
            append("原图备份 ID：").append(result.backupId).append("\n")
            append("写入尺寸：").append(result.width).append("×").append(result.height).append("\n")
            append(result.verifyNote)
            result.warning?.let { append("\n\n注意：").append(it) }
            append("\n\n建议强制停止钱包应用后重新打开，使其重新加载卡面。")
        }

        AlertDialog.Builder(this)
            .setTitle("替换完成")
            .setMessage(message)
            .setPositiveButton("强制停止应用") { _, _ -> forceStopTarget() }
            .setNegativeButton("稍后", null)
            .show()
    }

    private fun forceStopTarget() {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { FileOps.forceStop(pkg) }.getOrDefault(false) }
            toast(if (ok) "已强制停止 $pkg" else "强制停止失败，请手动在系统设置中停止应用")
        }
    }

    private fun cacheSizeKb(): Int {
        val max = Runtime.getRuntime().maxMemory()
        return (max / 8 / 1024).toInt().coerceAtLeast(8 * 1024)
    }

    private inner class CardAdapter : RecyclerView.Adapter<CardAdapter.VH>() {

        inner class VH(val b: ItemCardBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = cards.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            if (position !in cards.indices) return
            val item = cards[position]

            holder.b.tvName.text = item.name
            holder.b.ivCard.tag = item.name
            holder.b.ivCard.setImageDrawable(null)
            holder.itemView.setOnClickListener { showCard(item.name) }

            val cached = memoryCache.get(item.name)
            if (cached != null && !cached.isRecycled) {
                holder.b.ivCard.setImageBitmap(cached)
                return
            }

            val targetName = item.name
            lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    loadBitmap(FileOps.join(dir, targetName), 420)
                }
                if (bitmap == null) return@launch
                memoryCache.put(targetName, bitmap)
                if (holder.b.ivCard.tag == targetName) {
                    holder.b.ivCard.setImageBitmap(bitmap)
                }
            }
        }
    }

    companion object {
        const val EXTRA_PKG = "extra_pkg"
        const val EXTRA_DIR = "extra_dir"
    }
}
