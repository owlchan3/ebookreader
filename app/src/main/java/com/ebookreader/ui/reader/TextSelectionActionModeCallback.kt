package com.ebookreader.ui.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import com.ebookreader.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.readium.navigator.common.SelectionController
import org.readium.r2.shared.ExperimentalReadiumApi

/**
 * 自定义文字选择菜单：替换 WebView 默认的系统菜单（复制/全选/分享等），
 * 只保留「复制」和「分享」。选中文字后弹出的浮动工具栏仍由系统绘制，
 * 但菜单项完全由我们控制。
 */
@OptIn(ExperimentalReadiumApi::class)
class TextSelectionActionModeCallback(
    private val context: Context,
    private val selectionController: SelectionController<*>,
    private val scope: CoroutineScope,
) : ActionMode.Callback {

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        mode.menuInflater.inflate(R.menu.menu_text_selection, menu)
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_copy -> {
                scope.launch {
                    val text = selectionController.currentSelection()?.text
                    if (!text.isNullOrBlank()) {
                        copyToClipboard(text)
                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                    }
                    mode.finish()
                }
                return true
            }
            R.id.action_share -> {
                scope.launch {
                    val text = selectionController.currentSelection()?.text
                    if (!text.isNullOrBlank()) share(text)
                    mode.finish()
                }
                return true
            }
        }
        return false
    }

    override fun onDestroyActionMode(mode: ActionMode) {}

    private fun copyToClipboard(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("selection", text))
    }

    private fun share(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "分享选中文字"))
    }
}
