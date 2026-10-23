package com.mark.tvhome

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.unit.Dp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// City used for the weather widget (looked up via Open-Meteo, no API key needed).
private const val WEATHER_CITY = "Lahore"

class MainActivity : ComponentActivity() {
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HomeScreen(resumeTick.intValue) }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }
}

data class AppEntry(
    val pkg: String,
    val label: String,
    val subtitle: String,
    val icon: ImageBitmap,
    val intent: Intent,
)

private fun Drawable.toImageBitmap(size: Int = 160): ImageBitmap {
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    setBounds(0, 0, size, size)
    draw(canvas)
    return bmp.asImageBitmap()
}

private fun categoryName(category: Int): String = when (category) {
    ApplicationInfo.CATEGORY_GAME -> "Game"
    ApplicationInfo.CATEGORY_AUDIO -> "Music & audio"
    ApplicationInfo.CATEGORY_VIDEO -> "Video"
    ApplicationInfo.CATEGORY_IMAGE -> "Photos"
    ApplicationInfo.CATEGORY_SOCIAL -> "Social"
    ApplicationInfo.CATEGORY_NEWS -> "News"
    ApplicationInfo.CATEGORY_MAPS -> "Maps"
    ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Tools"
    else -> "App"
}

private fun loadApps(ctx: Context): List<AppEntry> {
    val pm = ctx.packageManager
    val leanback = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return (pm.queryIntentActivities(leanback, 0) + pm.queryIntentActivities(launcher, 0))
        .filter { it.activityInfo.packageName != ctx.packageName }
        .distinctBy { it.activityInfo.packageName }
        .map { ri ->
            val pkg = ri.activityInfo.packageName
            AppEntry(
                pkg = pkg,
                label = ri.loadLabel(pm).toString(),
                subtitle = categoryName(ri.activityInfo.applicationInfo.category),
                icon = ri.loadIcon(pm).toImageBitmap(),
                intent = Intent(Intent.ACTION_MAIN).apply {
                    setClassName(pkg, ri.activityInfo.name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
        .sortedBy { it.label.lowercase() }
}

private data class Stats(val usedMb: Long, val totalMb: Long, val load: String?)

private fun readStats(ctx: Context): Stats {
    val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    val load = try {
        File("/proc/loadavg").readText().split(" ").first()
    } catch (e: Exception) {
        null
    }
    return Stats((info.totalMem - info.availMem) / 1048576, info.totalMem / 1048576, load)
}

private fun weatherText(code: Int): String = when (code) {
    0 -> "Clear"
    1, 2 -> "Partly cloudy"
    3 -> "Overcast"
    45, 48 -> "Fog"
    in 51..57 -> "Drizzle"
    in 61..67, in 80..82 -> "Rain"
    in 71..77, 85, 86 -> "Snow"
    in 95..99 -> "Thunderstorm"
    else -> ""
}

private fun httpGet(url: String): JSONObject {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 8000
    conn.readTimeout = 8000
    return try {
        JSONObject(conn.inputStream.bufferedReader().readText())
    } finally {
        conn.disconnect()
    }
}

private suspend fun fetchWeather(): String? = withContext(Dispatchers.IO) {
    try {
        val geo = httpGet("https://geocoding-api.open-meteo.com/v1/search?name=$WEATHER_CITY&count=1")
            .getJSONArray("results").getJSONObject(0)
        val lat = geo.getDouble("latitude")
        val lon = geo.getDouble("longitude")
        val cur = httpGet(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,weather_code",
        ).getJSONObject("current")
        val temp = Math.round(cur.getDouble("temperature_2m"))
        val desc = weatherText(cur.getInt("weather_code"))
        "$temp°C  ${if (desc.isNotEmpty()) "· $desc  " else ""}· $WEATHER_CITY"
    } catch (e: Exception) {
        null
    }
}

private val Ink = Color.White
private fun glass(alpha: Float) = Color.White.copy(alpha = alpha)

@Composable
private fun Label(
    text: String,
    size: Int,
    weight: FontWeight,
    alpha: Float = 1f,
    modifier: Modifier = Modifier,
    align: TextAlign = TextAlign.Center,
) {
    BasicText(
        text = text,
        modifier = modifier,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(color = Ink.copy(alpha = alpha), fontSize = size.sp, fontWeight = weight, textAlign = align),
    )
}

private val Pinned = listOf("com.google.android.youtube.tv", "com.android.vending", "com.android.chrome")

@Composable
private fun Card(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 200.dp,
    height: Dp = 120.dp,
    focusRequester: FocusRequester? = null,
    icon: @Composable BoxScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.07f else 1f, label = "cardScale")
    val shape = RoundedCornerShape(22.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .size(width = width, height = height)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(if (focused) Color(0xF2303036) else Color(0xD91A1A1C))
            .border(if (focused) 2.dp else 1.dp, glass(if (focused) 0.9f else 0.08f), shape)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(50.dp).clip(RoundedCornerShape(14.dp)).background(glass(0.07f)),
            content = icon,
        )
        Spacer(Modifier.height(10.dp))
        Label(title, 16, FontWeight.Bold, 1f, Modifier.fillMaxWidth())
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Label(subtitle, 12, FontWeight.Normal, 0.55f, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun AppIcon(app: AppEntry, size: Dp) =
    Image(app.icon, contentDescription = app.label, modifier = Modifier.size(size))

@Composable
fun HomeScreen(resumeTick: Int) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf(emptyList<AppEntry>()) }
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    var weather by remember { mutableStateOf<String?>(null) }
    var stats by remember { mutableStateOf(readStats(ctx)) }
    var drawerOpen by remember { mutableStateOf(false) }
    val rowState = rememberLazyListState()
    val firstFocus = remember { FocusRequester() }
    val drawerCardFocus = remember { FocusRequester() }
    val gridFirstFocus = remember { FocusRequester() }

    val pinned = Pinned.mapNotNull { pkg -> apps.firstOrNull { it.pkg == pkg } }

    LaunchedEffect(resumeTick) { apps = withContext(Dispatchers.Default) { loadApps(ctx) } }
    LaunchedEffect(apps.isNotEmpty()) { delay(300); runCatching { firstFocus.requestFocus() } }
    var drawerWasOpened by remember { mutableStateOf(false) }
    LaunchedEffect(drawerOpen) {
        if (drawerOpen) drawerWasOpened = true
        if (drawerWasOpened) {
            delay(150)
            runCatching { (if (drawerOpen) gridFirstFocus else drawerCardFocus).requestFocus() }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(60_000L - now.second * 1000L)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            weather = fetchWeather() ?: weather
            delay(30 * 60_000L)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            stats = withContext(Dispatchers.IO) { readStats(ctx) }
            delay(3_000)
        }
    }

    BackHandler(enabled = drawerOpen) { drawerOpen = false }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Image(
            painter = painterResource(R.drawable.wallpaper),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // Scrims keep the clock and cards readable on a bright wallpaper.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color(0x99000000),
                    0.35f to Color(0x1A000000),
                    0.6f to Color(0x33000000),
                    1f to Color(0xE6000000),
                ),
            ),
        )

        Column(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 36.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column {
                    Label(now.format(DateTimeFormatter.ofPattern("h:mm")), 84, FontWeight.Light)
                    Label(
                        now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())),
                        20, FontWeight.Medium, 0.85f,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Label(weather ?: "Weather unavailable", 18, FontWeight.Medium, 0.95f)
                    Spacer(Modifier.height(10.dp))
                    val shape = RoundedCornerShape(14.dp)
                    Box(
                        Modifier.clip(shape).background(Color(0x99000000)).border(1.dp, glass(0.12f), shape)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Label(
                            "RAM ${stats.usedMb} / ${stats.totalMb} MB" + (stats.load?.let { "  ·  Load $it" } ?: ""),
                            14, FontWeight.Normal, 0.85f,
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // The row is removed while the drawer is open so the D-pad can't reach it.
            if (!drawerOpen && apps.isNotEmpty()) {
                LazyRow(
                    state = rowState,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(vertical = 20.dp, horizontal = 10.dp),
                ) {
                    items(pinned, key = { it.pkg }) { app ->
                        Card(
                            app.label, app.subtitle,
                            onClick = { ctx.startActivity(app.intent) },
                            focusRequester = if (app === pinned.first()) firstFocus else null,
                        ) { AppIcon(app, 38.dp) }
                    }
                    item(key = "drawer") {
                        Card(
                            "Apps", "All installed apps",
                            onClick = { drawerOpen = true },
                            focusRequester = if (pinned.isEmpty()) firstFocus else drawerCardFocus,
                        ) { Label("▦", 26, FontWeight.Bold) }
                    }
                }
            }
        }

        if (drawerOpen) {
            Box(Modifier.fillMaxSize().background(Color(0xF7000000)).padding(horizontal = 56.dp, vertical = 36.dp)) {
                Column {
                    Label("All apps", 30, FontWeight.SemiBold, modifier = Modifier.fillMaxWidth(), align = TextAlign.Start)
                    Spacer(Modifier.height(4.dp))
                    Label("Press Back to close", 14, FontWeight.Normal, 0.5f, Modifier.fillMaxWidth(), TextAlign.Start)
                    Spacer(Modifier.height(16.dp))
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(150.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(12.dp),
                    ) {
                        itemsIndexed(apps, key = { _, a -> a.pkg }) { index, app ->
                            Card(
                                app.label, null,
                                onClick = { drawerOpen = false; ctx.startActivity(app.intent) },
                                width = 150.dp, height = 104.dp,
                                focusRequester = if (index == 0) gridFirstFocus else null,
                            ) { AppIcon(app, 38.dp) }
                        }
                    }
                }
            }
        }
    }
}
