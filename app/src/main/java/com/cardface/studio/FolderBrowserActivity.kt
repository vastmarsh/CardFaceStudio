package com.cardface.studio

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cardface.studio.databinding.ActivityFolderBrowserBinding
import com.cardface.studio.databinding.ItemEntryBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FolderBrowserActivity : AppCompatActivity() {

    private lateinit var b: ActivityFolderBrowserBinding
    private var currentPath: String = ""
    private val entries = ArrayList<FileEntry>()
    private lateinit var adapter: EntryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityFolderBrowserBinding.inflate(layoutInflater)
        setContentView(b.root)

        adapter = EntryAdapter(entries) { entry ->
            if (entry.isDir) {
                navigateTo(FileOps.join(currentPath, entry.name))
            }
        }
        b.rvEntries.layoutManager = LinearLayoutManager(this)
        b.rvEntries.adapter = adapter
        b.rvEntries.itemAnimator = null

        b.btnUp.setOnClickListener {
            val parent = FileOps.parent(currentPath)
            if (parent == null) {
                toast("已经是根目录")
            } else {
                navigateTo(parent)
            }
        }
        b.btnRefreshDir.setOnClickListener { load(currentPath) }
        b.btnSelectDir.setOnClickListener { confirmSelection() }

        currentPath = resolveStartPath()
        load(currentPath)
    }

    private fun resolveStartPath(): String {
        val explicit = intent.getStringExtra(EXTRA_START)
        if (!explicit.isNullOrBlank()) return explicit

        val pkg = intent.getStringExtra(EXTRA_PKG)
        if (!pkg.isNullOrBlank()) {
            val candidates = listOf(
                "/sdcard/Android/data/$pkg",
                "/storage/emulated/0/Android/data/$pkg",
                "/sdcard/Android/data/$pkg/files",
                "/storage/emulated/0/Android/data/$pkg/files",
                "/data/data/$pkg",
                "/data/user/0/$pkg"
            )
            for (candidate in candidates) {
                val ok = runCatching { FileOps.isDir(candidate) }.getOrDefault(false)
                if (ok) return candidate
            }
        }
        return "/sdcard"
    }

    private fun navigateTo(path: String) {
        currentPath = path
        load(path)
    }

    private fun load(path: String) {
        b.progress.visibility = View.VISIBLE
        b.tvEmpty.visibility = View.GONE
        b.tvPath.text = path

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { FileOps.list(path) } }
            b.progress.visibility = View.GONE

            result.fold(
                onSuccess = { list ->
                    entries.clear()
                    entries.addAll(list)
                    adapter.notifyDataSetChanged()
                    if (list.isEmpty()) {
                        b.tvEmpty.visibility = View.VISIBLE
                        b.tvEmpty.text = "空目录"
                    }
                },
                onFailure = { error ->
                    entries.clear()
                    adapter.notifyDataSetChanged()
                    b.tvEmpty.visibility = View.VISIBLE
                    b.tvEmpty.text = "无法打开该目录：\n${error.message ?: "未知错误"}\n\n/sdcard/Android/data 下的目录 shell 身份即可访问；/data/data 下的目录才需要 Shizuku 以 root 身份运行。"
                }
            )
        }
    }

    private fun confirmSelection() {
        if (currentPath.isBlank()) return
        setResult(RESULT_OK, Intent().putExtra(EXTRA_SELECTED, currentPath))
        finish()
    }

    private class EntryAdapter(
        private val data: List<FileEntry>,
        private val onClick: (FileEntry) -> Unit
    ) : RecyclerView.Adapter<EntryAdapter.VH>() {

        class VH(val b: ItemEntryBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = data.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = data[position]
            holder.b.tvName.text = item.name
            if (item.isDir) {
                holder.b.tvKind.text = "目录"
                holder.b.tvKind.setTextColor(holder.itemView.context.getColor(R.color.brand))
                holder.b.tvName.setTextColor(holder.itemView.context.getColor(R.color.text_primary))
                holder.b.tvMeta.visibility = View.GONE
                holder.itemView.setOnClickListener { onClick(item) }
            } else {
                holder.b.tvKind.text = "文件"
                holder.b.tvKind.setTextColor(holder.itemView.context.getColor(R.color.text_secondary))
                holder.b.tvName.setTextColor(holder.itemView.context.getColor(R.color.text_secondary))
                holder.b.tvMeta.visibility = View.GONE
                holder.itemView.setOnClickListener(null)
            }
        }
    }

    companion object {
        const val EXTRA_PKG = "extra_pkg"
        const val EXTRA_START = "extra_start"
        const val EXTRA_SELECTED = "extra_selected"
    }
}
