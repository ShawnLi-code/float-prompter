package com.shawn.floatprompter.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class VersionInfo(
    val tagName: String,
    val versionName: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val hasNewVersion: Boolean
)

object UpdateManager {
    private const val GITHUB_REPO = "ShawnLi-code/float-prompter"
    private const val GITHUB_LATEST_API = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /**
     * 检查 GitHub 最新发布的正式版本（强制禁用 CDN 与本地缓存）
     */
    suspend fun checkLatestVersion(currentVersionName: String): Result<VersionInfo> = withContext(Dispatchers.IO) {
        try {
            val timestamp = System.currentTimeMillis()
            val request = Request.Builder()
                .url("$GITHUB_LATEST_API?_t=$timestamp")
                .addHeader("Accept", "application/vnd.github.v3+json")
                .addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                .addHeader("Pragma", "no-cache")
                .get()
                .build()

            var jsonObj: JsonObject? = null
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                jsonObj = gson.fromJson(body, JsonObject::class.java)
            } else {
                // 如果 /releases/latest 返回异常，尝试备用 /releases?per_page=1 列表接口
                val fallbackReq = Request.Builder()
                    .url("https://api.github.com/repos/$GITHUB_REPO/releases?per_page=1&_t=$timestamp")
                    .addHeader("Accept", "application/vnd.github.v3+json")
                    .addHeader("Cache-Control", "no-cache")
                    .get()
                    .build()
                val fallbackResp = client.newCall(fallbackReq).execute()
                if (fallbackResp.isSuccessful) {
                    val arr = gson.fromJson(fallbackResp.body?.string() ?: "", com.google.gson.JsonArray::class.java)
                    if (arr != null && arr.size() > 0) {
                        jsonObj = arr.get(0).asJsonObject
                    }
                }
            }

            if (jsonObj == null) {
                return@withContext Result.failure(Exception("无法连接到 GitHub 检查更新，请检查网络！"))
            }

            val tagName = jsonObj.get("tag_name")?.asString ?: ""
            val releaseNotes = jsonObj.get("body")?.asString ?: "优化了使用体验与系统稳定性"

            // 查找 assets 中的 apk 下载链接
            var downloadUrl = ""
            val assets = jsonObj.getAsJsonArray("assets")
            if (assets != null) {
                for (elem in assets) {
                    val asset = elem.asJsonObject
                    val name = asset.get("name")?.asString ?: ""
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        downloadUrl = asset.get("browser_download_url")?.asString ?: ""
                        break
                    }
                }
            }

            // 如果 assets 没取到，使用 tag 默认地址
            if (downloadUrl.isBlank() && tagName.isNotBlank()) {
                downloadUrl = "https://github.com/$GITHUB_REPO/releases/download/$tagName/FloatPrompter-$tagName.apk"
            }

            val remoteVersion = tagName.removePrefix("v").trim()
            val localVersion = currentVersionName.removePrefix("v").trim()

            val isNewer = compareVersions(remoteVersion, localVersion) > 0

            Result.success(
                VersionInfo(
                    tagName = tagName,
                    versionName = remoteVersion,
                    releaseNotes = releaseNotes,
                    downloadUrl = downloadUrl,
                    hasNewVersion = isNewer
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 版本号比较：如 1.0.1 vs 1.0.0
     */
    private fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split(".").mapNotNull { it.toIntOrNull() }
        val parts2 = v2.split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(parts1.size, parts2.size)

        for (i in 0 until maxLen) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) return p1.compareTo(p2)
        }
        return 0
    }

    /**
     * 下载 APK 并通过 FileProvider 调起系统覆盖安装
     * 全程保留应用数据、保留权限、无需卸载！
     */
    suspend fun downloadAndInstallApk(
        activity: Activity,
        downloadUrl: String,
        onProgress: (Int) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // 加速镜像策略（国内直连 GitHub release 镜像）
            val urlsToTry = listOf(
                "https://ghfast.top/$downloadUrl",
                "https://ghproxy.net/$downloadUrl",
                downloadUrl
            )

            var apkFile: File? = null
            var downloadSuccess = false

            // 使用版本专属的独立文件名，防止系统安装器读取旧版本缓存
            val safeFileName = "update_${downloadUrl.substringAfterLast('/').substringBeforeLast('.')}_${System.currentTimeMillis()}.apk"

            for (targetUrl in urlsToTry) {
                try {
                    val request = Request.Builder().url(targetUrl).get().build()
                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        val body = response.body
                        if (body != null) {
                            val contentLength = body.contentLength()
                            val destFile = File(activity.cacheDir, safeFileName)
                            if (destFile.exists()) destFile.delete()

                            body.byteStream().use { input ->
                                FileOutputStream(destFile).use { output ->
                                    val buffer = ByteArray(8192)
                                    var bytesRead: Int
                                    var totalBytesRead = 0L

                                    while (input.read(buffer).also { bytesRead = it } != -1) {
                                        output.write(buffer, 0, bytesRead)
                                        totalBytesRead += bytesRead
                                        if (contentLength > 0) {
                                            val progress = ((totalBytesRead * 100) / contentLength).toInt()
                                            withContext(Dispatchers.Main) {
                                                onProgress(progress)
                                            }
                                        }
                                    }
                                    output.flush()
                                }
                            }

                            if (destFile.exists() && destFile.length() > 1024 * 500) {
                                apkFile = destFile
                                downloadSuccess = true
                                break
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("UpdateManager", "Mirror $targetUrl failed: ${e.message}, trying next...")
                }
            }

            if (!downloadSuccess || apkFile == null) {
                return@withContext Result.failure(Exception("安装包下载失败，请检查网络连接！"))
            }

            withContext(Dispatchers.Main) {
                installApk(activity, apkFile)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 调起系统安装器覆盖更新（不丢数据、不重置权限）
     */
    fun installApk(context: Context, apkFile: File) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            intent.setDataAndType(apkUri, "application/vnd.android.package-archive")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "无法唤起安装器: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
