package me.ash.reader.domain.service

import android.content.Context
import android.util.Log
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import me.ash.reader.domain.data.SyncLogger
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.security.WebDavSecurityKey
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.repository.GroupDao
import me.ash.reader.infrastructure.android.NotificationHelper
import me.ash.reader.infrastructure.di.DefaultDispatcher
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.rss.RssHelper
import java.io.File
import android.provider.Settings
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.rometools.rome.feed.synd.SyndEntry
import com.thegrizzlylabs.sardineandroid.DavResource
import com.thegrizzlylabs.sardineandroid.Sardine
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.ash.reader.infrastructure.di.ApplicationScope
import me.ash.reader.ui.ext.dataStore
import java.io.FileWriter
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Calendar
import java.util.Date
import kotlin.collections.filter
import kotlin.collections.map
import kotlin.io.bufferedReader

@OptIn(FlowPreview::class)

class WebDavRssService
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val articleDao: ArticleDao,
    private val feedDao: FeedDao,
    private val rssHelper: RssHelper,
    private val notificationHelper: NotificationHelper,
    private val groupDao: GroupDao,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    private val workManager: WorkManager,
    private val accountService: AccountService,
    private val syncLogger: SyncLogger,
    @ApplicationScope private val applicationScope: CoroutineScope,
) :
    LocalRssService(
        context,
        articleDao,
        feedDao,
        rssHelper,
        notificationHelper,
        groupDao,
        ioDispatcher,
        defaultDispatcher,
        workManager,
        accountService,
        syncLogger
    )  {
    private val PUT_INTERVAL_MILLIS:Long = 1000 * 60 * 5;    // 5 分钟上传一次
    private val PULL_INTERVAL_MILLIS:Long = 1000 * 60 * 30;    // 30 分钟读取一次
    private val LAST_PULL_TIME = longPreferencesKey("LAST_READ_TIME")
    private val LAST_PUT_DAY = longPreferencesKey("LAST_PUT_DAY")
    private var lastPutTime = 0L;

    val androidId: String = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ANDROID_ID
    ) ?: ""

    private val needPutCount = MutableStateFlow(0)
    init {
        // needPutCount大于0，并且5分钟没有变化，PUT文件
        applicationScope.launch {
            needPutCount
                .filter { it > 0 }
                .debounce(PUT_INTERVAL_MILLIS)
                .collect {
                    // ✅ 5 分钟没变化，执行这里！
                    Log.d("WebDavRssService", "put file")
                    syncReadStatus(emptySet(), false)
                }
        }
    }

    private suspend fun getWebDavHandler(account: Account = accountService.getCurrentAccount()):Sardine {
        return WebDavSecurityKey(account.securityKey).run{
            val sardine = OkHttpSardine()
            sardine.setCredentials(username, password)
            sardine
        }
    }

    private suspend fun getWebDavPath(fileName:String = "", account: Account = accountService.getCurrentAccount()):String {
        return WebDavSecurityKey(account.securityKey).run{
            return serverUrl + "/ReadYou/" + fileName;
        }
    }

    override suspend fun validCredentials(account: Account): Boolean {
        try {
            val handle =  getWebDavHandler(account)
            handle.createDirectory(getWebDavPath("", account))
            return true
        } catch (e: Exception) {
            Log.e("WebDavRssService","create path err", e)
            return false
        }
    }

    override suspend fun syncReadStatus(articleIds: Set<String>, isUnread: Boolean): Set<String> {
        val (current, hourStart, todayStart) = getCurrentTimeInfo()

        val fileName = "${androidId}_AM_${todayStart}.txt"    // AM = ArticleModify
        val file = File(context.filesDir, fileName)

        if (articleIds.isNotEmpty()) {
            val textContent =
                "${current}_${if (isUnread) "U" else "R"}:${articleIds.joinToString(",")}\n"

            // 每天创建一个文件，追加写入文件中（自动创建文件，不会覆盖）
            FileWriter(file, true).use {
                it.write(textContent)
            }
        }

        putFile(false, fileName, file, current, todayStart)
        return articleIds
    }

    // 将几天前或全部标志为已读
    override suspend fun markAsRead(
        groupId: String?,
        feedId: String?,
        articleId: String?,
        before: Date?,
        isUnread: Boolean,
    ) {

        super.markAsRead(groupId, feedId, articleId, before, isUnread)
        // 查询15s内被更新为已读状态的文章
        val accountId = accountService.getCurrentAccountId()
        val recentUpdatedId = articleDao.queryArticleIdInUpdateAfter(accountId, false, 1500)
        syncReadStatus(recentUpdatedId.toSet(), false)
    }

    override suspend fun markAsStarred(articleId: String, isStarred: Boolean) {
        super.markAsStarred(articleId, isStarred)

        val (current, hourStart, todayStart) = getCurrentTimeInfo()

        val fileName = "${androidId}_AM_${todayStart}.txt"    // AM = ArticleModify
        val file = File(context.filesDir, fileName)


        val textContent =
            "${current}_${if (isStarred) "S" else "D"}:${articleId}\n"  // S = Star, D = DeleteStar

        // 每天创建一个文件，追加写入文件中（自动创建文件，不会覆盖）
        FileWriter(file, true).use {
            it.write(textContent)
        }

        putFile(true, fileName, file, current, todayStart)
    }

    suspend fun putFile(forcePut:Boolean, fileName:String, file: File, current:Long, todayStart:Long) {
        val handler = getWebDavHandler()

        val lastPutDay = getStoreTime(LAST_PUT_DAY)
        if (lastPutDay != todayStart) {
            // 上次PUT与当前时间不是同一天了（跨天了）,把上次缓存的内容再提交一次
            val lastPutDayFileName = "${androidId}_AM_${lastPutDay}.txt"
            val lastPutDayFile =  File(context.filesDir, lastPutDayFileName)
            if (lastPutDayFile.exists()) {
                try {
                    handler.put(getWebDavPath(lastPutDayFileName), lastPutDayFile.readBytes(),)
                } catch (e: Exception) {
                    Log.e("WebDavRssService","update file err:" + lastPutDayFileName, e)
                }
            }
        }

        if ( (forcePut || current - lastPutTime >= PUT_INTERVAL_MILLIS) && file.exists()) {
            try {
                handler.put(getWebDavPath(fileName), file.readBytes())
                lastPutTime = current
                needPutCount.value = 0
                updateStoreTime(LAST_PUT_DAY, todayStart)
            } catch (e: Exception) {
                Log.e("WebDavRssService","update file err:" + fileName, e)
            }
        } else {
            needPutCount.value += 1;
        }
    }




    override suspend fun syncState(accountId: Int,
                                   feedId: String?,
                                   groupId: String?) {
        if (feedId != null) {   // 刷新指定feed时，不同步WebDav
            return
        }

        val lastPullTime  = getStoreTime(LAST_PULL_TIME)
        val currentTime = System.currentTimeMillis()
//        if (currentTime - lastPullTime < PULL_INTERVAL_MILLIS) {
//            return
//        }

        val parserDiff = mutableMapOf<Pair<String, String>, Long>()

        try {
            val handle = getWebDavHandler();
            val resources: List<DavResource> = handle.list(getWebDavPath())

            val needReadFileNames = resources.filter { !it.isDirectory }
                .map { it.name }
                .filter { name -> !name.startsWith(androidId) }
                .filter { name -> name.contains("_AM_") }
                .filter { name ->
                    name.substringAfterLast("_")
                        .substringBefore(".").toLong() >= getDayStartTimestamp(lastPullTime)
                }
            for (fileName in needReadFileNames) {
                val diffContent =
                    handle.get(getWebDavPath(fileName)).bufferedReader().use { it.readText() }
                val parseResult = parseDiffContent(diffContent, lastPullTime - PUT_INTERVAL_MILLIS * 2)
                parserDiff.putAll(parseResult)
            }
        } catch (e: Exception) {
            Log.e("WebDavRssService","pull err", e)
            return
        }

        try {
            val updateToRead =  mutableListOf<Pair<String, Long>>()
            val updateToUnRead =  mutableListOf<Pair<String, Long>>()
            val updateToStar =  mutableListOf<Pair<String, Long>>()
            val updateToDelete =  mutableListOf<Pair<String, Long>>()

            for (entry in parserDiff) {
                when (entry.key.second) {
                    "R" -> {
                        updateToRead.add(entry.key.first to entry.value)
                    }
                    "U" -> {
                        updateToUnRead.add(entry.key.first to entry.value)
                    }
                    "S" -> {
                        updateToStar.add(entry.key.first to entry.value)
                    }
                    "D" -> {
                        updateToDelete.add(entry.key.first to entry.value)
                    }
                }
            }

            if (updateToRead.isNotEmpty()) {
                articleDao.markAsReadAfterUpdateAt(updateToRead, false)
            }
            if (updateToUnRead.isNotEmpty()) {
                articleDao.markAsReadAfterUpdateAt(updateToUnRead, true)
            }
            if (updateToStar.isNotEmpty()) {
                articleDao.markAsStarAfterUpdateAt(updateToStar, true)
            }
            if (updateToDelete.isNotEmpty()) {
                articleDao.markAsStarAfterUpdateAt(updateToStar, false)
            }

            // 更新时间
            updateStoreTime(LAST_PULL_TIME, currentTime)
        } catch (e: Exception) {
            Log.e("WebDavRssService","update article fail", e)
            return
        }
    }

    suspend fun parseDiffContent(text:String, lastPullTime:Long) :
            MutableMap<Pair<String, String>, Long> {
        val result = mutableMapOf<Pair<String, String>, Long>()

        // 按行拆分，过滤空行
        val lines = text.lines().filter { it.isNotBlank() }
        for (line in lines) {
            // 1. 拆分时间戳 和 右侧内容
            val parts = line.split("_", limit = 2)
            if (parts.size < 2) continue

            // 时间戳转 Long
            val timeStamp = parts[0].toLongOrNull() ?: continue
            val rightPart = parts[1] // 例：R:xxx,xxx

            // 2. 拿到类型 R / U
            val type = rightPart.firstOrNull()?.toString() ?: continue
            if (type !in listOf("R", "U", "S", "D")) continue

            // 3. 拿到所有 ID
            val idContent = rightPart.substringAfter(":")
            val idList = idContent.split(",").map { it.trim() }

            // 4. 存入 map：key = Pair(id, type)，value = 时间戳
            for (id in idList) {
                if (id.isNotEmpty() && timeStamp >= lastPullTime) {
                    result[Pair(id, type)] = timeStamp

                }
            }
        }
        return result
    }

    override suspend fun canDoSync(): Boolean {
        return true;
    }

    override fun createArticleId(accountId: Int, entry: SyndEntry):String {
        return "WD&" + entry.link.md5();
    }


    fun String.md5(): String {
        val bytes = this.toByteArray(StandardCharsets.UTF_8)
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(bytes)
        return digest.joinToString("") { "%02x".format(it) } // 小写
    }

    // 直接返回 3 个值：当前、0点、小时整点
    fun getCurrentTimeInfo(): Triple<Long, Long, Long> {
        val cal = Calendar.getInstance()
        // 当前时间
        val current = cal.timeInMillis

        // 当前小时整点
        val hourCal = cal.clone() as Calendar
        hourCal.set(Calendar.MINUTE, 0)
        hourCal.set(Calendar.SECOND, 0)
        hourCal.set(Calendar.MILLISECOND, 0)
        val hourStart = hourCal.timeInMillis

        // 今天0点
        val todayCal = cal.clone() as Calendar
        todayCal.set(Calendar.HOUR_OF_DAY, 0)
        todayCal.set(Calendar.MINUTE, 0)
        todayCal.set(Calendar.SECOND, 0)
        todayCal.set(Calendar.MILLISECOND, 0)
        val todayStart = todayCal.timeInMillis

        return Triple(current, hourStart,todayStart)
    }

    suspend fun getStoreTime(key:Preferences.Key<Long>): Long {
        return context.dataStore.data
            .map { it[key] ?: 0L }
            .first()
    }

    suspend fun updateStoreTime(key:Preferences.Key<Long>, time:Long) {
        context.dataStore.edit {
            it[key] = time
        }
    }

    fun getDayStartTimestamp(timestamp: Long): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestamp
        calendar.set(Calendar.HOUR_OF_DAY, 0)    // 时
        calendar.set(Calendar.MINUTE, 0)         // 分
        calendar.set(Calendar.SECOND, 0)         // 秒
        calendar.set(Calendar.MILLISECOND, 0)    // 毫秒
        return calendar.timeInMillis
    }
}