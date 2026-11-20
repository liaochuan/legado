package io.legado.app.ui.book.import.remote

import android.os.Bundle
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppConst
import io.legado.app.databinding.ActivityOpdsBookBinding
import io.legado.app.help.glide.GlideHeaders
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.getToolbarTextColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.remote.RemoteBook
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.viewbindingdelegate.viewBinding

class OpdsBookInfoActivity : VMBaseActivity<ActivityOpdsBookBinding, OpdsBookInfoViewModel>() {

    override val binding by viewBinding(ActivityOpdsBookBinding::inflate)
    override val viewModel by viewModels<OpdsBookInfoViewModel>()

    private lateinit var remoteBook: RemoteBook
    private var serverId = AppConst.DEFAULT_WEBDAV_ID
    private var downloading = false

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        serverId = intent.getLongExtra("serverID", AppConst.DEFAULT_WEBDAV_ID)
        val book = GSON.fromJsonObject<RemoteBook>(intent.getStringExtra("book")).getOrNull()
        if (book == null) {
            finish()
            return
        }
        remoteBook = book
        binding.root.setBackgroundColor(backgroundColor)
        binding.toolBar.setBackgroundColor(primaryColor)
        val toolbarTextColor = getToolbarTextColor(false)
        binding.toolBar.setTitleTextColor(toolbarTextColor)
        binding.toolBar.navigationIcon?.setTint(toolbarTextColor)
        binding.toolBar.setNavigationOnClickListener { finish() }
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolBar) { view, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(view.paddingLeft, top, view.paddingRight, view.paddingBottom)
            insets
        }
        bind()
    }

    private fun bind() {
        binding.tvName.text = remoteBook.filename
        binding.tvAuthor.text = remoteBook.author.orEmpty()
        binding.tvAuthor.isVisible = !remoteBook.author.isNullOrBlank()
        binding.tvKind.text = remoteBook.categories.joinToString(" · ")
        binding.tvKind.isVisible = remoteBook.categories.isNotEmpty()
        binding.tvFormats.text = getString(
            R.string.format_label,
            formats().joinToString(" / ")
        )
        val hasIntro = !remoteBook.intro.isNullOrBlank()
        binding.llIntro.isVisible = hasIntro
        binding.tvIntro.text = remoteBook.intro.orEmpty()
        loadCover()
        binding.btDownload.setOnClickListener { chooseFormatAndDownload() }
    }

    private fun formats(): List<String> {
        return remoteBook.links
            .map { it.extension?.uppercase() ?: it.label ?: remoteBook.contentType }
            .distinct()
            .ifEmpty { listOf(remoteBook.contentType) }
    }

    private fun loadCover() {
        val coverUrl = remoteBook.coverUrl
        if (coverUrl.isNullOrBlank()) {
            binding.ivCover.setImageResource(R.drawable.image_cover_default)
            return
        }
        Glide.with(this)
            .load(GlideUrl(coverUrl, GlideHeaders(HashMap(remoteBook.coverHeaders))))
            .placeholder(R.drawable.image_cover_default)
            .error(R.drawable.image_cover_default)
            .into(binding.ivCover)
    }

    private fun chooseFormatAndDownload() {
        val options = formats()
        if (options.size > 1) {
            selector(R.string.select_format, options) { _, format, _ ->
                download(format)
            }
        } else {
            download(options.firstOrNull())
        }
    }

    private fun download(format: String?) {
        if (downloading) return
        downloading = true
        binding.btDownload.isEnabled = false
        binding.btDownload.text = getString(R.string.downloading)
        viewModel.download(serverId, remoteBook, format) { success ->
            downloading = false
            binding.btDownload.isEnabled = true
            binding.btDownload.text = getString(R.string.download_to_shelf)
            if (success) {
                setResult(RESULT_OK)
                finish()
            }
        }
    }
}
