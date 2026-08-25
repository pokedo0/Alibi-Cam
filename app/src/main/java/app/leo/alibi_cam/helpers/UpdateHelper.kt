package app.leo.alibi_cam.helpers

import android.util.Log
import app.leo.alibi_cam.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 更新信息数据类
 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val message: String,
    val downloadUrl: String,
)

/**
 * 更新检查帮助类
 *
 * 通过 GitHub Releases API 检查最新发布版本并比对语义化版本号。
 */
object UpdateHelper {
    private const val TAG = "UpdateHelper"

    // ── GitHub Latest Release API ──
    private const val GITHUB_RELEASE_API_URL =
        "https://api.github.com/repos/pokedo0/Alibi-Cam/releases/latest"

    /**
     * 将版本号字符串解析为整数列表，例如 "v1.2.3" -> [1, 2, 3]
     */
    fun parseVersionParts(version: String): List<Int> {
        val clean = version.trim().removePrefix("v").removePrefix("V").split("-")[0]
        return clean.split(".").mapNotNull { it.toIntOrNull() }
    }

    /**
     * 判断远端版本是否高于本地版本
     */
    fun isNewerVersion(remoteTag: String, currentVersionName: String): Boolean {
        val remoteParts = parseVersionParts(remoteTag)
        val currentParts = parseVersionParts(currentVersionName)
        val maxLen = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    /**
     * 将语义化版本号转换为整数版本代码，供 DataStore 忽略更新对比使用
     */
    fun versionToCode(versionName: String): Int {
        val parts = parseVersionParts(versionName)
        val major = parts.getOrElse(0) { 0 }
        val minor = parts.getOrElse(1) { 0 }
        val patch = parts.getOrElse(2) { 0 }
        return major * 10000 + minor * 100 + patch
    }

    /**
     * 从 GitHub API 获取最新 Release 并比对版本号。
     * 若有新版本则返回 UpdateInfo，否则返回 null。
     */
    suspend fun checkForUpdate(
        currentVersionName: String = BuildConfig.VERSION_NAME,
        currentVersionCode: Int = BuildConfig.VERSION_CODE,
    ): UpdateInfo? {
        return withContext(Dispatchers.IO) {
            Log.d(
                TAG,
                "开始检查更新: currentVersionName=$currentVersionName, currentVersionCode=$currentVersionCode, url=$GITHUB_RELEASE_API_URL"
            )
            try {
                val conn = (URL(GITHUB_RELEASE_API_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "Alibi-Cam-App")
                    connectTimeout = 10_000
                    readTimeout = 10_000
                }

                val responseCode = conn.responseCode
                Log.d(TAG, "GitHub API HTTP 响应码: $responseCode")
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    Log.w(TAG, "获取最新 Release 失败，HTTP 状态码: $responseCode")
                    conn.disconnect()
                    return@withContext null
                }

                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                Log.d(TAG, "获取到 Release 信息 payload 长度: ${body.length}")

                val json = JSONObject(body)
                val tagName = json.getString("tag_name")
                val releaseName = json.optString("name", tagName)
                val releaseBody = json.optString("body", "").ifBlank {
                    "发现新版本 $tagName，请前往 GitHub Releases 下载。"
                }
                val htmlUrl = json.optString("html_url", "https://github.com/pokedo0/Alibi-Cam/releases")
                val remoteVersionCode = versionToCode(tagName)

                Log.d(
                    TAG,
                    "解析 GitHub Release: tag=$tagName, name=$releaseName, remoteCode=$remoteVersionCode, htmlUrl=$htmlUrl"
                )

                if (isNewerVersion(tagName, currentVersionName)) {
                    Log.i(
                        TAG,
                        "发现新版本: remoteTag=$tagName > currentVersion=$currentVersionName"
                    )
                    UpdateInfo(
                        versionCode = remoteVersionCode,
                        versionName = tagName,
                        message = releaseBody,
                        downloadUrl = htmlUrl,
                    )
                } else {
                    Log.i(
                        TAG,
                        "当前已是最新版本: currentVersion=$currentVersionName, remoteTag=$tagName"
                    )
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "GitHub API 检查更新发生异常", e)
                null
            }
        }
    }
}
