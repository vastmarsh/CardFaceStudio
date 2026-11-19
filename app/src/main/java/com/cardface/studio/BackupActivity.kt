package com.cardface.studio

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cardface.studio.databinding.ActivityBackupBinding
import com.cardface.studio.databinding.ItemBackupBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BackupActivity : AppCompatActivity() {

    private lateinit var b: ActivityBackupBinding
    private val records = ArrayList<BackupRecord>()
    private lateinit var adapter: BackupAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityBackupBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.tvBackupDir.text = "备份目录：${BackupStore.rootDir(this).absolutePath}"

        adapter = BackupAdapter(
            records,
            onRestore = { restore(it) },
            onDelete = { confirmDelete(it) }
        )
        b.rvBackups.layoutManager = LinearLayoutManager(this)
        b.rvBackups.adapter = adapter
        b.rvBackups.itemAnimator = null

        b.btnRefreshBackup.setOnClickListener { load() }
        b.btnRestoreAll.setOnClickListener { confirmRestoreAll() }

        load()
    }

    private fun load() {
        b.progress.visibility = View.VISIBLE
        b.tvEmpty.visibility = View.GONE

        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { runCatching { BackupStore.list(this@BackupActivity) }.getOrDefault(emptyList()) }
            b.progress.visibility = View.GONE
            records.clear()
            records.addAll(list)
            adapter.notifyDataSetChanged()
            b.tvCount.text = "共 ${list.size} 条备份"
            if (list.isEmpty()) {
                b.tvEmpty.visibility = View.VISIBLE
            }
        }
    }

    private fun restore(record: BackupRecord) {
        AlertDialog.Builder(this)
            .setTitle("还原原卡面")
            .setMessage("将把备份中的「${record.fileName}」写回：\n${record.originalPath}\n\n当前内容会被覆盖（可再次备份）。")
            .setPositiveButton("还原") { _, _ -> doRestore(record) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doRestore(record: BackupRecord) {
        val progress = showProgress(this, "正在还原", record.fileName).also { it.show() }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { CardEngine.restore(record) } }
            progress.dismiss()
            result.fold(
                onSuccess = { toastLong(it) },
                onFailure = { toastLong("还原失败：${it.message}") }
            )
        }
    }

    private fun confirmRestoreAll() {
        if (records.isEmpty()) {
            toast("没有可还原的备份")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("恢复全部备份")
            .setMessage("将把 ${records.size} 条备份全部写回各自的原始路径（按时间从旧到新执行）。\n\n该操作会覆盖钱包应用当前的卡面文件。")
            .setPositiveButton("开始恢复") { _, _ -> doRestoreAll() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doRestoreAll() {
        val progress = showProgress(this, "正在批量还原", "共 ${records.size} 条备份…").also { it.show() }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                var ok = 0
                var failed = 0
                val failures = StringBuilder()
                for (record in records.sortedBy { it.createdAt }) {
                    val r = runCatching { CardEngine.restore(record) }
                    if (r.isSuccess) {
                        ok++
                    } else {
                        failed++
                        if (failures.length < 600) {
                            failures.append(record.fileName).append("：").append(r.exceptionOrNull()?.message).append("\n")
                        }
                    }
                }
                Triple(ok, failed, failures.toString())
            }
            progress.dismiss()

            AlertDialog.Builder(this@BackupActivity)
                .setTitle("批量还原完成")
                .setMessage(
                    "成功 ${result.first} 条，失败 ${result.second} 条。" +
                        if (result.third.isBlank()) "" else "\n\n失败明细：\n${result.third}"
                )
                .setPositiveButton("好", null)
                .show()
            load()
        }
    }

    private fun confirmDelete(record: BackupRecord) {
        AlertDialog.Builder(this)
            .setTitle("删除备份")
            .setMessage("将删除「${record.fileName}」的这份备份（${record.timeText()}）。\n删除后无法再用它还原原卡面。")
            .setPositiveButton("删除") { _, _ ->
                val ok = BackupStore.delete(record)
                toast(if (ok) "已删除备份" else "删除失败")
                load()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private class BackupAdapter(
        private val data: List<BackupRecord>,
        private val onRestore: (BackupRecord) -> Unit,
        private val onDelete: (BackupRecord) -> Unit
    ) : RecyclerView.Adapter<BackupAdapter.VH>() {

        class VH(val b: ItemBackupBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemBackupBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = data.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = data[position]
            holder.b.tvFileName.text = item.fileName
            holder.b.tvMeta.text = "${item.timeText()} · ${formatSize(item.size)} · ${item.pkg}"
            holder.b.tvOriginPath.text = item.originalPath
            holder.b.btnRestore.setOnClickListener { onRestore(item) }
            holder.b.btnDelete.setOnClickListener { onDelete(item) }
        }
    }
}
