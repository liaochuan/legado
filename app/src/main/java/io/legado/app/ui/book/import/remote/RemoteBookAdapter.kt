package io.legado.app.ui.book.import.remote

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.AppConst
import io.legado.app.databinding.ItemRemoteBookBinding
import io.legado.app.help.glide.GlideHeaders
import io.legado.app.model.remote.RemoteBook
import io.legado.app.utils.ConvertUtils

class RemoteBookAdapter(context: Context, val callBack: CallBack) :
    RecyclerAdapter<RemoteBook, ItemRemoteBookBinding>(context) {

    var selected = hashSetOf<RemoteBook>()
    var checkableCount = 0
    var isOpds = false
    private var selectionMode = false

    override fun getViewBinding(parent: ViewGroup): ItemRemoteBookBinding {
        return ItemRemoteBookBinding.inflate(inflater, parent, false)
    }

    override fun onCurrentListChanged() {
        upCheckableCount()
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemRemoteBookBinding,
        item: RemoteBook,
        payloads: MutableList<Any>
    ) {
        binding.run {
            if (payloads.isEmpty()) {
                tvName.text = item.filename
                if (item.isDir) {
                    ivCover.isVisible = false
                    ivIcon.isVisible = true
                    cbSelect.isVisible = false
                    tvAuthor.isVisible = false
                    tvIntro.isVisible = false
                    llBrief.isVisible = false
                } else {
                    ivIcon.isVisible = false
                    ivCover.isVisible = true
                    tvAuthor.text = item.author.orEmpty()
                    tvAuthor.isVisible = !item.author.isNullOrBlank()
                    tvIntro.text = item.intro.orEmpty()
                    tvIntro.isVisible = !item.intro.isNullOrBlank()
                    llBrief.isVisible = true
                    tvTag.text = if (item.isOnBookShelf) {
                        context.getString(R.string.downloaded)
                    } else {
                        item.links.mapNotNull { it.extension?.uppercase() }
                            .distinct()
                            .ifEmpty { listOf(item.contentType) }
                            .joinToString(" / ")
                    }
                    tvSize.text = if (item.size > 0) {
                        ConvertUtils.formatFileSize(item.size)
                    } else {
                        ""
                    }
                    tvDate.text = if (item.lastModify > 0) {
                        AppConst.dateFormat.format(item.lastModify)
                    } else {
                        ""
                    }
                    loadCover(binding, item)
                }
            }
            cbSelect.isVisible = !item.isDir && !item.isOnBookShelf && selectionMode
            cbSelect.isChecked = selected.contains(item)
        }
    }

    private fun loadCover(binding: ItemRemoteBookBinding, item: RemoteBook) {
        val coverUrl = item.coverUrl
        if (coverUrl.isNullOrBlank()) {
            Glide.with(context).clear(binding.ivCover)
            binding.ivCover.setImageResource(R.drawable.image_cover_default)
            return
        }
        Glide.with(context)
            .load(GlideUrl(coverUrl, GlideHeaders(HashMap(item.coverHeaders))))
            .placeholder(R.drawable.image_cover_default)
            .error(R.drawable.image_cover_default)
            .into(binding.ivCover)
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemRemoteBookBinding) {
        holder.itemView.setOnClickListener {
            getItem(holder.bindingAdapterPosition)?.let { item ->
                when {
                    item.isDir -> callBack.openDir(item)
                    item.isOnBookShelf -> callBack.startRead(item)
                    !isOpds -> toggleSelect(item)
                    selectionMode -> toggleSelect(item)
                    else -> callBack.openBookInfo(item)
                }
            }
        }
        holder.itemView.setOnLongClickListener {
            getItem(holder.bindingAdapterPosition)?.let { item ->
                when {
                    item.isOnBookShelf -> if (!isOpds) callBack.addToBookShelfAgain(item)
                    isOpds -> {
                        if (!selectionMode) {
                            selectionMode = true
                            notifyDataSetChanged()
                        }
                        toggleSelect(item)
                    }
                }
            }
            true
        }
    }

    private fun toggleSelect(item: RemoteBook) {
        if (item.isDir || item.isOnBookShelf) return
        if (!selected.add(item)) {
            selected.remove(item)
        }
        val position = getItems().indexOf(item)
        if (position >= 0) {
            notifyItemChanged(position, true)
        }
        callBack.upCountView()
    }

    fun exitSelectionMode() {
        if (!selectionMode && selected.isEmpty()) return
        selectionMode = false
        selected.clear()
        notifyDataSetChanged()
        callBack.upCountView()
    }

    private fun upCheckableCount() {
        checkableCount = getItems().count { !it.isDir && !it.isOnBookShelf }
        callBack.upCountView()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun selectAll(selectAll: Boolean) {
        if (selectAll) {
            selectionMode = true
            getItems().forEach {
                if (!it.isDir && !it.isOnBookShelf) {
                    selected.add(it)
                }
            }
        } else {
            selected.clear()
            selectionMode = false
        }
        notifyDataSetChanged()
        callBack.upCountView()
    }

    fun revertSelection() {
        selectionMode = true
        getItems().forEach {
            if (!it.isDir && !it.isOnBookShelf) {
                if (!selected.add(it)) {
                    selected.remove(it)
                }
            }
        }
        notifyDataSetChanged()
        callBack.upCountView()
    }

    fun removeSelection() {
        for (i in getItems().lastIndex downTo 0) {
            if (getItem(i) in selected) {
                removeItem(i)
            }
        }
    }

    interface CallBack {
        fun openDir(remoteBook: RemoteBook)
        fun openBookInfo(remoteBook: RemoteBook)
        fun upCountView()
        fun startRead(remoteBook: RemoteBook)
        fun addToBookShelfAgain(remoteBook: RemoteBook)
    }
}
