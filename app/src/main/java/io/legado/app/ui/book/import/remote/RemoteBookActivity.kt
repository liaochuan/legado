package io.legado.app.ui.book.import.remote

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.SubMenu
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.isGone
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.model.remote.RemoteBook
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.import.BaseImportBookActivity
import io.legado.app.ui.widget.SelectActionBar
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.GSON
import io.legado.app.utils.find
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.visible
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import java.io.File

/**
 * 展示远程书籍
 */
class RemoteBookActivity : BaseImportBookActivity<RemoteBookViewModel>(),
    RemoteBookAdapter.CallBack,
    SelectActionBar.CallBack,
    ServersDialog.Callback {

    override val viewModel by viewModels<RemoteBookViewModel>()
    private val adapter by lazy { RemoteBookAdapter(this, this) }
    private var groupMenu: SubMenu? = null
    private val bookInfoLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            if (viewModel.isSearching) {
                viewModel.searchKey?.let { viewModel.search(it) }
            } else {
                upPath()
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        searchView.queryHint = getString(R.string.screen) + " • " + getString(R.string.remote_book)
        onBackPressedDispatcher.addCallback(this) {
            when {
                viewModel.isSearching -> {
                    viewModel.exitSearch()
                    adapter.selectAll(false)
                    binding.tvPath.text = viewModel.dirList.joinToString(" / ") { it.filename }
                }
                !goBackDir() -> finish()
            }
        }
        lifecycleScope.launch {
            if (!setBookStorage()) {
                finish()
                return@launch
            }
            initView()
            initEvent()
            launch {
                viewModel.dataFlow.conflate().collect { sortedRemoteBooks ->
                    binding.refreshProgressBar.isAutoLoading = false
                    binding.tvEmptyMsg.isGone = sortedRemoteBooks.isNotEmpty()
                    if (sortedRemoteBooks.isEmpty() && viewModel.lastError == null) {
                        binding.tvEmptyMsg.text = getString(
                            when {
                                viewModel.isSearching -> R.string.no_search_result
                                viewModel.isOpds -> R.string.empty_category
                                else -> R.string.empty
                            }
                        )
                    }
                    adapter.setItems(sortedRemoteBooks)
                    delay(500)
                }
            }
            viewModel.initData {
                adapter.isOpds = viewModel.isOpds
                invalidateOptionsMenu()
                upPath()
                if (!viewModel.isOpds && !LocalConfig.webDavBookHelpVersionIsLast) {
                    showHelp("webDavBookHelp")
                }
            }
        }
    }

    override fun observeLiveBus() {
        viewModel.permissionDenialLiveData.observe(this) {
            localBookTreeSelect.launch {
                title = getString(R.string.select_book_folder)
            }
        }
        viewModel.errorLiveData.observe(this) { message ->
            if (message.isNotBlank()) {
                binding.tvEmptyMsg.text = message
                binding.tvEmptyMsg.visible()
            } else {
                binding.tvEmptyMsg.text = getString(R.string.empty)
            }
        }
        viewModel.pendingFormatChoice.observe(this) { books ->
            if (books.isNullOrEmpty()) return@observe
            val options = viewModel.formatOptions(books)
            if (options.isEmpty()) return@observe
            viewModel.consumeFormatChoice()
            selector(R.string.select_format, options) { _, format, _ ->
                downloadSelected(format)
            }
        }
    }

    private fun initView() {
        binding.layTop.setBackgroundColor(backgroundColor)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.selectActionBar.setMainActionText(R.string.add_to_bookshelf)
        binding.selectActionBar.setCallBack(this)
        binding.tvEmptyMsg.setOnClickListener { upPath() }
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (!viewModel.isOpds || dy <= 0) return
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                val itemCount = recyclerView.adapter?.itemCount ?: 0
                if (itemCount > 0 && lastVisible >= itemCount - 5) {
                    viewModel.loadMore()
                }
            }
        })
    }

    private fun sortCheck(sortKey: RemoteBookSort) {
        if (viewModel.sortKey == sortKey) {
            viewModel.sortAscending = !viewModel.sortAscending
        } else {
            viewModel.sortAscending = true
            viewModel.sortKey = sortKey
        }
    }

    private fun initEvent() {
        binding.tvGoBack.setOnClickListener {
            goBackDir()
        }
    }


    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.book_remote, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_refresh -> upPath()
            R.id.menu_server_config -> showDialogFragment<ServersDialog>()
            R.id.menu_log -> showDialogFragment<AppLogDialog>()
            R.id.menu_help -> showHelp("webDavBookHelp")
            R.id.menu_sort_name -> {
                item.isChecked = true
                sortCheck(RemoteBookSort.Name)
                upPath()
            }
            R.id.menu_sort_time -> {
                item.isChecked = true
                sortCheck(RemoteBookSort.Default)
                upPath()
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.menu_sort)?.isVisible = !viewModel.isOpds
        menu.findItem(R.id.menu_help)?.isVisible = !viewModel.isOpds
        groupMenu = menu.findItem(R.id.menu_sort)?.subMenu
        groupMenu?.setGroupCheckable(R.id.menu_group_sort, true, true)
        groupMenu?.findItem(R.id.menu_sort_name)?.isChecked =
            viewModel.sortKey == RemoteBookSort.Name
        groupMenu?.findItem(R.id.menu_sort_time)?.isChecked =
            viewModel.sortKey == RemoteBookSort.Default
        return super.onPrepareOptionsMenu(menu)
    }

    override fun revertSelection() {
        adapter.revertSelection()
    }

    override fun selectAll(selectAll: Boolean) {
        adapter.selectAll(selectAll)
    }

    override fun onClickSelectBarMainAction() {
        if (viewModel.isOpds && adapter.selected.any { it.links.size > 1 }) {
            viewModel.requestFormatChoice(adapter.selected)
            return
        }
        downloadSelected(null)
    }

    private fun downloadSelected(forcedFormat: String?) {
        binding.refreshProgressBar.isAutoLoading = true
        viewModel.addToBookshelf(adapter.selected, forcedFormat) {
            adapter.selectAll(false)
            binding.refreshProgressBar.isAutoLoading = false
        }
    }

    private fun goBackDir(): Boolean {
        if (viewModel.dirList.isEmpty()) {
            return false
        }
        viewModel.dirList.removeLastOrNull()
        upPath()
        return true
    }

    private fun upPath() {
        binding.tvGoBack.isEnabled = viewModel.dirList.isNotEmpty()
        binding.tvPath.text = if (viewModel.isOpds) {
            viewModel.dirList.joinToString(" / ") { it.filename }
        } else {
            var path = if (viewModel.isDefaultWebdav) {
                "books" + File.separator
            } else {
                File.separator
            }
            viewModel.dirList.forEach {
                path = path + it.filename + File.separator
            }
            path
        }
        adapter.exitSelectionMode()
        viewModel.loadRemoteBookList(
            viewModel.dirList.lastOrNull()?.path
        ) {
            binding.refreshProgressBar.isAutoLoading = it
        }
    }

    override fun openDir(remoteBook: RemoteBook) {
        viewModel.clearSearchState()
        viewModel.dirList.add(remoteBook)
        upPath()
    }

    override fun openBookInfo(remoteBook: RemoteBook) {
        if (!viewModel.isOpds) return
        val intent = Intent(this, OpdsBookInfoActivity::class.java).apply {
            putExtra("serverID", viewModel.serverId)
            putExtra("book", GSON.toJson(remoteBook))
        }
        bookInfoLauncher.launch(intent)
    }

    override fun upCountView() {
        binding.selectActionBar.upCountView(adapter.selected.size, adapter.checkableCount)
    }

    override fun onDialogDismiss(tag: String) {
        viewModel.initData {
            upPath()
        }
    }

    override fun onSearchTextChange(newText: String?) {
        if (!viewModel.isOpds) {
            viewModel.updateCallBackFlow(newText)
        }
    }

    override fun onSearchTextSubmit(newText: String?) {
        if (viewModel.isOpds && !newText.isNullOrBlank()) {
            adapter.selectAll(false)
            viewModel.search(newText)
            binding.tvPath.text = getString(R.string.search_prefix, newText)
        }
    }

    private fun showRemoteBookDownloadAlert(
        remoteBook: RemoteBook,
        onDownloadFinish: (() -> Unit)? = null
    ) {
        alert(
            R.string.draw,
            R.string.archive_not_found
        ) {
            okButton {
                viewModel.addToBookshelf(hashSetOf(remoteBook)) {
                    onDownloadFinish?.invoke()
                }
            }
            noButton()
        }
    }

    override fun startRead(remoteBook: RemoteBook) {
        val downloadFileName = remoteBook.localFileName ?: remoteBook.filename
        if (!ArchiveUtils.isArchive(downloadFileName)) {
            appDb.bookDao.getBookByFileName(downloadFileName)?.let {
                startReadBook(it)
            }
        } else {
            AppConfig.defaultBookTreeUri ?: return
            val downloadArchiveFileDoc = FileDoc.fromUri(Uri.parse(AppConfig.defaultBookTreeUri), true)
                .find(downloadFileName)
            if (downloadArchiveFileDoc == null) {
                showRemoteBookDownloadAlert(remoteBook) {
                    startRead(remoteBook)
                }
            } else {
                onArchiveFileClick(downloadArchiveFileDoc)
            }
        }
    }

    override fun addToBookShelfAgain(remoteBook: RemoteBook) {
        alert(getString(R.string.sure), "是否重新加入书架？") {
            yesButton {
                binding.refreshProgressBar.isAutoLoading = true
                viewModel.addToBookshelf(hashSetOf(remoteBook)) {
                    binding.refreshProgressBar.isAutoLoading = false
                }
            }
            noButton()
        }
    }

}
