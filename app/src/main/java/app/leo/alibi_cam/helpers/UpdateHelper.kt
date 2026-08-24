package app.leo.alibi_cam.helpers

import android.util.Log
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
 * 从 Gitee raw 文件拉取 update.json，比对版本号。
 */
object UpdateHelper {
    private const val TAG = "UpdateHelper"

    // ── Gitee 仓库 raw 文件地址 ──
    private const val UPDATE_URL =
        "https://gitee.com/zhanglinleo1-maker/Alibi-Cam/raw/master/update.json"

    /**
     * 从 Gitee 拉取 update.json，如果远端版本 > 当前版本则返回 UpdateInfo，
     * 否则返回 null（无更新或网络错误）。
     */
    suspend fun checkForUpdate(currentVersionCode: Int): UpdateInfo? {
        return withContext(Dispatchers.IO) {
            try {
                val conn = (URL(UPDATE_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                }

                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()

                val json = JSONObject(body)
                val remoteVersionCode = json.getInt("versionCode")

                if (remoteVersionCode > currentVersionCode) {
                    UpdateInfo(
                        versionCode = remoteVersionCode,
                        versionName = json.getString("versionName"),
                        message = json.getString("message"),
                        downloadUrl = json.getString("downloadUrl"),
                    )
                } else {
                    Log.i(TAG, "已是最新版本 (current=$currentVersionCode, remote=$remoteVersionCode)")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "检查更新失败", e)
                null
            }
        }
    }
}
