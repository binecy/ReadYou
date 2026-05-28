package me.ash.reader.domain.service

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.ash.reader.domain.data.SyncLogger
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.account.security.WebDavSecurityKey
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.feed.FeedWithArticle
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.repository.GroupDao
import me.ash.reader.infrastructure.android.NotificationHelper
import me.ash.reader.infrastructure.di.DefaultDispatcher
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.rss.RssHelper
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString.Companion.encode
import timber.log.Timber


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
    override suspend fun validCredentials(account: Account): Boolean {
        return WebDavSecurityKey(account.securityKey).run{
            Log.i("WebDavRssService", "serverUrl:"+serverUrl)
            Log.i("WebDavRssService", "username:"+username)
            Log.i("WebDavRssService", "password:"+password)

            if (serverUrl == null) {
                return false
            }
            try {
                val webDavPath = serverUrl + "/ReadYou/"
                val request = Request.Builder()
                    .url(webDavPath)
                    .method("MKCOL", null) // 核心：MKCOL 建目录
                    .addHeader("Authorization", basicAuth(username, password))
                    .build()
                val resp = OkHttpClient().newCall(request).execute()
                resp.code == 201 || resp.code == 405
            } catch (e: Exception) {
                Log.e("WebDavRssService","create path err", e)
                false
            }
        }
    }

    private fun basicAuth(username:String?, password:String?): String {
        val auth = "$username:$password"
        return "Basic " + Base64.encodeToString(auth.toByteArray(), Base64.NO_WRAP)
    }
}