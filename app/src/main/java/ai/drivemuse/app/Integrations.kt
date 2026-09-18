package ai.drivemuse.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import ai.drivemuse.domain.*

object MusicHandoff {
    fun open(context: Context, track: Track?): String {
        val uri = if (track == null) Uri.parse("https://music.youtube.com/") else {
            if (!Policy.validTrackId(track.id)) return "올바른 음악 링크가 아닙니다"
            Uri.Builder().scheme("https").authority("music.youtube.com").path("watch").appendQueryParameter("v",track.id).build()
        }
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW,uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).setPackage("com.google.android.apps.youtube.music"))
            "YouTube Music을 열었습니다 · 재생 여부는 해당 앱에서 확인해 주세요"
        } catch (_: android.content.ActivityNotFoundException) { "YouTube Music이 설치되어 있지 않습니다" }
          catch (_: SecurityException) { "YouTube Music을 열 수 없습니다" }
    }
}
val DemoTracks = listOf(
    Track("demo0000001","Sunset Avenue","DriveMuse Sample",true,.35),
    Track("demo0000002","A Little Further","Northbound",false,.45),
    Track("demo0000003","City Lights","Slow Motion",true,.3),
    Track("demo0000004","Home Again","Evening Club",true,.4),
    Track("demo0000005","Coastal Line","Northbound",false,.5),
    Track("demo0000006","Blue Hour","DriveMuse Sample",true,.25)
)
