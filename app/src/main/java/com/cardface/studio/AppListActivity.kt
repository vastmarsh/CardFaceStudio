package com.cardface.studio

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cardface.studio.databinding.ActivityAppListBinding
import com.cardface.studio.databinding.ItemAppBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class AppListActivity : AppCompatActivity() {

    data class AppItem(val pkg: String, val label: String, val icon: Drawable?)

    private lateinit var b: ActivityAppListBinding
    private val all = ArrayList<AppItem>()
    private val visible = ArrayList<AppItem>()
    private lateinit var adapter: AppAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityAppListBinding.inflate(layoutInflater)
        setContentView(b.root)

        adapter = AppAdapter(visible) { item ->
            setResult(
                RESULT_OK,
                Intent()
                    .putExtra(EXTRA_PKG, item.pkg)
                    .putExtra(EXTRA_LABEL, item.label)
            )
            finish()
        }

        b.rvApps.layoutManager = LinearLayoutManager(this)
        b.rvApps.adapter = adapter
        b.rvApps.itemAnimator = null

        b.etSearch.doAfterTextChanged { filter(it?.toString().orEmpty()) }

        loadApps()
    }

    private fun loadApps() {
        b.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { queryLauncherApps() }
            all.clear()
            all.addAll(items)
            b.progress.visibility = View.GONE
            filter(b.etSearch.text?.toString().orEmpty())
        }
    }

    private fun queryLauncherApps(): List<AppItem> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = try {
            pm.queryIntentActivities(intent, 0)
        } catch (_: Throwable) {
            emptyList()
        }

        val map = LinkedHashMap<String, AppItem>()
        for (info in resolved) {
            val activityInfo = info.activityInfo ?: continue
            val pkg = activityInfo.packageName ?: continue
            if (pkg == packageName) continue
            val label = try {
                activityInfo.loadLabel(pm)?.toString() ?: pkg
            } catch (_: Throwable) {
                pkg
            }
            val icon = try {
                activityInfo.loadIcon(pm)
            } catch (_: Throwable) {
                null
            }
            if (!map.containsKey(pkg)) {
                map[pkg] = AppItem(pkg, label, icon)
            }
        }
        return map.values.sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

    private fun filter(query: String) {
        visible.clear()
        if (query.isBlank()) {
            visible.addAll(all)
        } else {
            val q = query.trim().lowercase(Locale.getDefault())
            visible.addAll(all.filter {
                it.label.lowercase(Locale.getDefault()).contains(q) || it.pkg.lowercase(Locale.getDefault()).contains(q)
            })
        }
        adapter.notifyDataSetChanged()

        val empty = visible.isEmpty()
        b.tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        b.tvEmpty.text = if (all.isEmpty()) {
            "未获取到应用列表，请确认已允许「读取已安装应用」相关信息。"
        } else {
            "没有匹配的应用"
        }
    }

    private class AppAdapter(
        private val data: List<AppItem>,
        private val onClick: (AppItem) -> Unit
    ) : RecyclerView.Adapter<AppAdapter.VH>() {

        class VH(val b: ItemAppBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = data.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = data[position]
            holder.b.tvLabel.text = item.label
            holder.b.tvPkg.text = item.pkg
            holder.b.ivIcon.setImageDrawable(item.icon)
            holder.itemView.setOnClickListener { onClick(item) }
        }
    }

    companion object {
        const val EXTRA_PKG = "extra_pkg"
        const val EXTRA_LABEL = "extra_label"
    }
}
