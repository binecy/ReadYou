package me.ash.reader.ui.component.webview

import android.app.Activity
import androidx.compose.material3.AlertDialog
import android.content.Context
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import me.ash.reader.domain.model.article.ArticleMark
import me.ash.reader.ui.page.adaptive.ArticleListReaderViewModel
import org.json.JSONObject
import java.util.Date
import java.util.UUID

/**
 * 文本选中时，自定义菜单，支持划线，想法，复制
 */
class CustomMenuWebView(
    context: Context,
    private val viewModel: ArticleListReaderViewModel?,
    externalClient: WebViewClient
) : WebView(context) {
    init {
        settings.javaScriptEnabled = true
        addJavascriptInterface(JSInterface(), "Android")

        externalClient.onPageFinishedCallback = {view, url ->
            if (viewModel == null) {
                Toast.makeText(context, "viewModel为空，无法加载文章划线", Toast.LENGTH_SHORT).show()
            } else {
                viewModel.queryArticleMark { articleMarks ->
                    // 🔥 关键：使用 post 切换到主线程
                    post {
                        articleMarks?.let {
                            if (articleMarks.isNotEmpty()) {
                                // todo 性能优化，articleMarks合并到一起处理
                                articleMarks.forEach { item ->
                                    view?.evaluateJavascript(
                                        highlightMarkJS(
                                            item.markTextJson,
                                            item.id,
                                            item.idea
                                        )
                                    ) { result ->
                                    }
                                }
                                Toast.makeText(
                                    context,
                                    "已加载${articleMarks.size}个文章划线",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
            }
        }

        webViewClient = externalClient
    }

    private fun highlightMarkJS(textsJson: String, markId: String, idea: String? = null): String {
        val ideaValue = if (idea == null) "null" else "'$idea'"

        return """
            (function() {
                var texts = $textsJson;
                var idea = $ideaValue;
                var markId = '$markId';
                
                var nodes = [];
                var w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
                var n;
                while (n = w.nextNode()) if (n.textContent.trim()) nodes.push(n);
                    
                texts.forEach(function(t) {
                    nodes.forEach(function(node) {
                        var idx = node.textContent.indexOf(t);
                        if (idx !== -1) {
                            try {
                                var s = document.createElement('span');
                                s.style.cssText = 'background:#FFF8DC;padding:2px 4px;border-radius:4px;';
                                s.textContent = t;
                                s.className = 'mark_' + markId;

                                if (idea && idea.length > 0) {
                                    s.style.textDecoration = 'underline dotted';  // 下划线 + 虚线样式
                                }
                                
                                s.onclick = function() {
                                    Android.showDialog(markId, idea);
                                };
                                var r = document.createRange();
                                r.setStart(node, idx);
                                r.setEnd(node, idx + t.length);
                                r.surroundContents(s);
                            } catch(e) {}
                        }
                    });
                });
               
                return 'success'
            })()
        """.trimIndent()
    }

    private fun getSelectedTextsJS():String {
        return """
            (function() {
                var selection = window.getSelection();
                var text = selection.toString().trim();
                
                var r = selection.getRangeAt(0);
                var texts = [];
                
                // 先处理 root 本身
                var root = r.commonAncestorContainer;
                if (root.nodeType === Node.TEXT_NODE && root.textContent.trim()) {
                    var s = root === r.startContainer ? r.startOffset : 0;
                    var e = root === r.endContainer ? r.endOffset : root.textContent.length;
                    if (s < e) texts.push(root.textContent.substring(s, e));
                }

                var w = document.createTreeWalker(
                    root,
                    NodeFilter.SHOW_TEXT,
                    {
                        acceptNode: function(node) {
                            // 🔥 过滤掉 root 本身
                            if (node === root) {
                                return NodeFilter.FILTER_REJECT;  // 拒绝 root
                            }
                            return NodeFilter.FILTER_ACCEPT;      // 接受其他节点
                        }
                    },
                    false
                );
                
                
                var n;
                while (n = w.nextNode()) {
                    if (r.intersectsNode(n) && n.textContent.trim()) {
                        var s = n === r.startContainer ? r.startOffset : 0;
                        var e = n === r.endContainer ? r.endOffset : n.textContent.length;
                        if (s < e) texts.push(n.textContent.substring(s, e));
                    }
                }
                selection.removeAllRanges();
                return {
                    texts: texts,
                    text:text
                }
            })()
        """.trimIndent()
    }


    inner class JSInterface {
        @JavascriptInterface
        fun showToast(text: String) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun showDialog(markId:String, text: String?) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                showDialog(showText = text?:"删减划线？", deleteProcessor = {
                    if (viewModel == null) {
                        Toast.makeText(context, "viewModel为空，无法保存", Toast.LENGTH_SHORT).show()
                    } else {
                        viewModel.deleteArticleMark(markId)

                        // Kotlin 中执行
                        val rmHighlightMarkJS = """
                            document.querySelectorAll('span.mark_$markId').forEach(function(span) {
                                var text = span.textContent;
                                var textNode = document.createTextNode(text);
                                span.parentNode.replaceChild(textNode, span);
                            });
                        """.trimIndent()
                        evaluateJavascript(rmHighlightMarkJS, null)
                    }
                })
            }
        }
    }

    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode {
        // 拦截菜单，替换成自定义的
        return super.startActionMode(CustomActionModeCallback(callback), type)
    }

    private inner class CustomActionModeCallback(private val originalCallback: ActionMode.Callback?) : ActionMode.Callback {
        val IDEA_ITEM_ID = 1001
        val MAKR_ITEM_ID = 1002
        val COPY_ITEM_ID = 1003

        override fun onCreateActionMode(mode: ActionMode?, menu: Menu): Boolean {
            // 清空系统菜单
            menu.clear()

            menu.add(Menu.NONE, IDEA_ITEM_ID, 0, "想法")
            menu.add(Menu.NONE, MAKR_ITEM_ID, 1, "划线")

            // 保留系统常用的"复制"功能（可选）
            menu.add(0, COPY_ITEM_ID, 2, "复制")
            return true
        }

        private val composeView = ComposeView(context)


        override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
            when (item?.itemId) {
                IDEA_ITEM_ID -> {
                    addIdea()

                    mode?.finish()
                    return true
                }
                MAKR_ITEM_ID -> {
                    addMark()

                    mode?.finish()
                    return true
                }
                COPY_ITEM_ID -> {
                    copyToClipboard()

                    mode?.finish()
                    return true
                }
            }
            return false
        }


        /**
         * 🔥 高亮选中的文字
         */
        private fun addMark() {
            evaluateJavascript(getSelectedTextsJS()) { result ->
                val json = JSONObject(result)
                val texts = json.getJSONArray("texts")
                val text = json.optString("text")

                if (viewModel == null) {
                    Toast.makeText(context, "viewModel为空，无法保存", Toast.LENGTH_SHORT).show()
                } else {
                    val testJson = texts.toString()
                    val markId = UUID.randomUUID().toString()
                    val am = ArticleMark(markId, "", Date(),  text, testJson, null)
                    viewModel.saveArticleMark(am)

                    evaluateJavascript(highlightMarkJS(testJson, markId)) {
                        Toast.makeText(context, "划线已保存", Toast.LENGTH_SHORT).show()
                    }
                }
            }

        }

        private fun addIdea() {
            evaluateJavascript(getSelectedTextsJS()) { result ->
                val json = JSONObject(result)
                val texts = json.getJSONArray("texts")
                val text = json.optString("text")

                showDialog(onConfirm = {idea ->
                    if (viewModel == null) {
                        Toast.makeText(context, "viewModel为空，无法保存", Toast.LENGTH_SHORT).show()
                    } else {
                        val testJson = texts.toString()
                        val markId = UUID.randomUUID().toString()
                        val am = ArticleMark(
                            markId,
                            "",
                            Date(),
                            text,
                            testJson,
                            idea
                        )
                        viewModel.saveArticleMark(am)

                        evaluateJavascript(highlightMarkJS(testJson,markId, idea)) {
                            Toast.makeText(context, "想法已保存", Toast.LENGTH_SHORT).show()
                        }
                    }
                })
            }
        }

        // 复制到剪贴板
        private fun copyToClipboard() {
            evaluateJavascript(getSelectedTextsJS()) { result ->
                val json = JSONObject(result)
                val text = json.optString("text")
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("text", text)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
            }
        }

        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
        override fun onDestroyActionMode(mode: ActionMode?) {}
    }

    private fun showDialog(onConfirm: (String) -> Unit = {}, showText: String? = null, deleteProcessor: (String) -> Unit = {}) {
        val activity = context as? Activity ?: return

        // ✅ 改为方法局部变量
        val dialogView = ComposeView(context).apply {
            // 设置布局参数使其覆盖全屏
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            setContent {
                var text by remember { mutableStateOf("") }
                var show by remember { mutableStateOf(true) }

                if (show) {
                    AlertDialog(
                        onDismissRequest = {
                            show = false
                            removeDialogView(this@apply)
                        },
                        text = {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (showText != null) {
                                    Text(showText)
                                } else {
                                    TextField(
                                        value = text,
                                        onValueChange = { text = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        placeholder = { Text("输入你的想法") },
                                        maxLines = 5,
                                        singleLine = false
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            if (showText != null) {
                                TextButton(
                                    onClick = {
                                        deleteProcessor.invoke(text)
                                        show = false
                                        removeDialogView(this@apply)
                                    }
                                ) { Text("删除") }
                            } else {
                                TextButton(
                                    onClick = {
                                        if (text.isNotBlank()) {
                                            onConfirm.invoke(text)
                                        }
                                        show = false
                                        removeDialogView(this@apply)
                                    }
                                ) { Text("确认") }
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = {
                                    show = false
                                    removeDialogView(this@apply)
                                }
                            ) { Text("取消") }
                        }
                    )
                }
            }
        }

        // 添加到decorView
        (activity.window.decorView as ViewGroup).addView(dialogView)
    }

    private fun removeDialogView(dialogView: ComposeView) {
        (dialogView.parent as? ViewGroup)?.removeView(dialogView)
    }
}