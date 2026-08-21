package com.example.roadlog

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.TextView
import java.io.File
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.tileprovider.tilesource.XYTileSource

/** Shared OSM tile configuration for every map in the app. */
object MapTileConfiguration {
    const val COPYRIGHT_URL = "https://www.openstreetmap.org/copyright"
    const val REPORT_MAP_ISSUE_URL = "https://www.openstreetmap.org/fixthemap"

    private const val OSM_TILE_URL = "https://tile.openstreetmap.org/"
    private const val CONTACT_EMAIL = "dynosups@gmail.com"
    private const val SUPPORT_URL = "https://github.com/sups23/roadlog-andrioid"

    val tileSource: OnlineTileSourceBase = XYTileSource(
        "OpenStreetMap Standard",
        0,
        19,
        256,
        ".png",
        arrayOf(OSM_TILE_URL),
        "© OpenStreetMap contributors",
        TileSourcePolicy(
            2,
            TileSourcePolicy.FLAG_NO_BULK or
                TileSourcePolicy.FLAG_NO_PREVENTIVE or
                TileSourcePolicy.FLAG_USER_AGENT_MEANINGFUL
        )
    )

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        val configuration = Configuration.getInstance()
        configuration.load(
            appContext,
            appContext.getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        )

        val cacheDirectory = File(appContext.filesDir, "tiles").apply { mkdirs() }
        configuration.osmdroidBasePath = appContext.filesDir
        configuration.osmdroidTileCache = cacheDirectory
        configuration.userAgentValue =
            "RoadLog/${BuildConfig.VERSION_NAME} (+$SUPPORT_URL; contact: $CONTACT_EMAIL)"
        configuration.tileDownloadThreads = 2
        configuration.tileDownloadMaxQueueSize = 40
        configuration.isDebugMapTileDownloader = BuildConfig.DEBUG
    }

    fun configureAttributionView(view: TextView) {
        val attribution = SpannableString("© OpenStreetMap contributors\nReport a map issue")
        attribution.setSpan(
            browserLink(view.context, COPYRIGHT_URL),
            2,
            29,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        attribution.setSpan(
            browserLink(view.context, REPORT_MAP_ISSUE_URL),
            30,
            attribution.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        view.text = attribution
        view.setTextColor(Color.WHITE)
        view.setLinkTextColor(Color.WHITE)
        view.movementMethod = LinkMovementMethod.getInstance()
        view.highlightColor = Color.TRANSPARENT
    }

    private fun browserLink(context: Context, url: String): ClickableSpan {
        return object : ClickableSpan() {
            override fun onClick(widget: View) {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }
    }
}
