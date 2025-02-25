package com.example.myefteling.presentation

import android.content.Context
import android.content.res.Resources
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.example.myefteling.R
import com.example.myefteling.presentation.theme.MyEftelingTheme
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import kotlin.coroutines.*
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.ButtonDefaults
import androidx.compose.ui.input.pointer.pointerInteropFilter
import android.view.MotionEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import android.os.Vibrator
import android.os.VibrationEffect
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.combinedClickable
import coil.compose.rememberImagePainter
import coil.size.Scale
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import coil.compose.rememberAsyncImagePainter

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class FetchRideDataWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val rideLabel = inputData.getString("rideLabel") ?: return@withContext Result.failure()

        try {
            val excludedIds = listOf(8235, 6175, 6154, 6172, 7236, 6362, 6180, 8841)
            val json = fetchJsonFromUrl()
            val result = json?.getAllRides(excludedIds)
            val rides = result?.first ?: emptyList()

            val specificRide = rides.find { it.label == rideLabel }
            if (specificRide != null && specificRide.waitTime != 1000) {
                sendNotification("Ride ${specificRide.label} is now open!")
                WorkManager.getInstance(applicationContext).cancelWorkById(id)
            }

            Result.success()
        } catch (e: Exception) {
            Result.failure()
        }
    }

    private fun sendNotification(message: String) {
        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "ride_update_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Ride Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.drawable.efteling)
            .setContentTitle("Ride Update")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notificationManager.notify(1, notification)
    }
}

class MainActivity : ComponentActivity() {
    enum class SortOption {
        TITLE,
        WAIT_TIME
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()

        super.onCreate(savedInstanceState)

        setTheme(android.R.style.Theme_DeviceDefault)

        setContent {
            MyEftelingTheme {
                val navController = rememberNavController()
                NavHost(navController, startDestination = "main") {
                    composable("main") { WearApp(navController) }
                    composable("detail/{tileLabel}") { backStackEntry ->
                        DetailScreen(backStackEntry.arguments?.getString("tileLabel").toString())
                    }
                    composable("sorting_options") { SortingOptionsScreen() }
                }
            }
        }
    }
}

data class TileItem(val id: Int, val label: String, val waitTime: Int)

@Composable
fun WearApp(navController: NavHostController) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background),
        contentAlignment = Alignment.Center
    ) {
        TimeText()
        TileList(navController)
    }
}

fun getDrawableIdByName(context: Context, name: String): Int {
    // Try to get the drawable resource ID by its name
    val resourceId = context.resources.getIdentifier(name.replace(" ","").replace("&", "en").replace("ñ","n"), "drawable", context.packageName)
    // Return the found resource ID or the default drawable (R.drawable.base) if not found
    return if (resourceId != 0) resourceId else R.drawable.base
}

@Composable
fun TileList(navController: NavHostController) {
    var tiles by remember { mutableStateOf<List<TileItem>>(emptyList()) }
    var rideMap by remember { mutableStateOf<Map<String, List<TileItem>>>(emptyMap()) }
    val listState = rememberLazyListState()
    var sortOption by remember { mutableStateOf(MainActivity.SortOption.TITLE) }
    var isLoading by remember { mutableStateOf(true) }
    val infiniteTransition = rememberInfiniteTransition()
    val isLoadingBaseVisible = remember { mutableStateOf(true) }
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    var currentImage by remember { mutableStateOf(R.drawable.loading_wheel) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000) // Delay time for switching images
            isLoadingBaseVisible.value = !isLoadingBaseVisible.value
            currentImage = if (currentImage == R.drawable.loading_base) {
                R.drawable.loading_base
            } else {
                R.drawable.loading_error
            }
        }
    }

    val excludedIds = listOf(8235, 6175, 6154, 6172, 7236, 6362, 6180, 8841) // Example excluded IDs
    var isError by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            val json = fetchJsonFromUrl()
            val result = json?.getAllRides(excludedIds)
            if (result != null) {
                tiles = result.first
                rideMap = result.second
            } else {
                isError = true
            }
        } catch (e: Exception) {
            Log.e("TileList", "Error fetching data", e)
            isError = true
        } finally {
            isLoading = false
        }
    }

    if (isLoading) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFede5d5))
        ){
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.loading_base),
                    contentDescription = "loading base",
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(24.dp)),
                    contentScale = ContentScale.Crop
                )
                Image(
                    painter = painterResource(id = R.drawable.loading_wheel),
                    contentDescription = "loading wheel",
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(24.dp))
                        .graphicsLayer(
                            rotationZ = rotation,
                            transformOrigin = TransformOrigin(0.48f, 0.35f)
                        ),
                    contentScale = ContentScale.Crop
                )
                Image(
                    painter = painterResource(id = R.drawable.loading_cover),
                    contentDescription = "loading cover",
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(24.dp)),
                    contentScale = ContentScale.Crop
                )
            }
        }
    } else if (isError) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFede5d5))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
            ) {
                Crossfade(
                    targetState = isLoadingBaseVisible.value,
                    animationSpec = tween(durationMillis = 1000) // Duration of the fade animation
                ) { isBaseVisible ->
                    if (isBaseVisible) {
                        Image(
                            painter = painterResource(id = R.drawable.loading_base),
                            contentDescription = "loading base",
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(24.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Image(
                            painter = painterResource(id = R.drawable.loading_error),
                            contentDescription = "loading base 2",
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(24.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
                Image(
                    painter = painterResource(id = R.drawable.loading_wheel),
                    contentDescription = "loading wheel",
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(24.dp)),
                    contentScale = ContentScale.Crop
                )
                Image(
                    painter = painterResource(id = R.drawable.loading_cover),
                    contentDescription = "loading cover",
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(24.dp)),
                    contentScale = ContentScale.Crop
                )
            }
        }
    } else {
        val sortedTiles = when (sortOption) {
            MainActivity.SortOption.TITLE -> tiles.sortedBy { it.label }
            MainActivity.SortOption.WAIT_TIME -> tiles.sortedBy { it.waitTime }
        }

        val context = LocalContext.current
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator


        Box(
            modifier = Modifier
                .background(Color(0xFFede5d5)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                Button(
                    onClick = {
                        sortOption =
                            if (sortOption == MainActivity.SortOption.TITLE) MainActivity.SortOption.WAIT_TIME else MainActivity.SortOption.TITLE
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(0.dp)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    sortOption =
                                        if (sortOption == MainActivity.SortOption.TITLE) MainActivity.SortOption.WAIT_TIME else MainActivity.SortOption.TITLE
                                },
                                onLongPress = {
                                    vibrator.vibrate(
                                        VibrationEffect.createOneShot(
                                            50,
                                            VibrationEffect.DEFAULT_AMPLITUDE
                                        )
                                    )
                                    navController.navigate("sorting_options")
                                }
                            )
                        },
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = Color(0xFF70A489) // Set the background color here
                    )
                ) {
                    Text(text = "Sort by ${if (sortOption == MainActivity.SortOption.TITLE) "Wait Time" else "Title"}")
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    items(sortedTiles.size) { index ->
                        val bottomPadding = if (index == sortedTiles.size - 1) 70.dp else 0.dp
                        Box(modifier = Modifier.padding(bottom = bottomPadding)) {
                            TileItemView(navController, sortedTiles[index], listState, index)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TileItemView(
    navController: NavHostController,
    tile: TileItem,
    listState: LazyListState,
    index: Int
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    // Calculate the offset in pixels
    val offsetPx = with(density) {
        listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.offset?.toDp() ?: 0.dp
    }
    var boxWidth by remember { mutableStateOf(0) }
    val currentPadding = if (offsetPx > 100.dp) {20.dp + (offsetPx-100.dp)/3 } else {if (offsetPx < 0.dp) {20.dp - offsetPx/3} else {20.dp}}

    Box(
        modifier = Modifier
            .padding(horizontal = currentPadding)
            .height(54.dp)
            .clickable {
                navController.navigate("detail/${tile.label}")
            }
            .onSizeChanged { boxWidth = it.width }
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = getDrawableIdByName(context, tile.label.lowercase())),
            contentDescription = tile.label,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(24.dp)),
            contentScale = ContentScale.Crop
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(if (tile.waitTime < 1000) Color(0x70808080) else Color(0xDD808080))
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            val fontSize = with(density) { (boxWidth/12).toSp() }

            Text(
                text = "${tile.label}\n".plus(if (tile.waitTime == 1000) "Gesloten" else if (tile.waitTime == 1001) "Loading..." else "${tile.waitTime} min"),
                color = Color.White,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.body1.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = fontSize,
                ),
            )
        }
    }
}


@Composable
fun DetailScreen(tileLabel: String) {
    var waitTime by remember { mutableStateOf(1001) }
    var associatedRides by remember { mutableStateOf<List<TileItem>>(emptyList()) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    val workManager = WorkManager.getInstance(context)
    val workInfos by workManager.getWorkInfosForUniqueWorkLiveData("FetchRideData_$tileLabel").observeAsState()
    val isWorkActive = workInfos?.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING  } == true


    LaunchedEffect(tileLabel) {
        coroutineScope.launch {
            try {
                val json = fetchJsonFromUrl()
                waitTime = (json?.getWaitTimeForRide(tileLabel ?: "") ?: 1001)
                val ridesMap = json?.getAllRides(emptyList())?.second
                associatedRides = ridesMap?.get(tileLabel) ?: emptyList()
            } catch (e: Exception) {
                waitTime = 1001
                Log.e("DetailScreen", "Error fetching data", e)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = getDrawableIdByName(LocalContext.current, tileLabel.lowercase())),
            contentDescription = "achtergrond",
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(24.dp)),
            contentScale = ContentScale.Crop
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "${tileLabel}: ".plus(if (waitTime == 1000) "Gesloten" else if (waitTime == 1001) "Loading..." else "${waitTime} min"),
                color = Color.White,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.body1.copy(
                    fontWeight = FontWeight.Bold
                )
            )
            associatedRides.forEach { ride ->
                Text(
                    text = "${ride.label}: ".plus(if (ride.waitTime == 1000) "Gesloten" else if (ride.waitTime == 1001) "Loading..." else "${ride.waitTime} min"),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.body1.copy(
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        }
        if (waitTime == 1000) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(90.dp)
                    .offset(y = 80.dp)
                    .clip(CircleShape)
                    //.background(if (isWorkActive) Color.Green else Color.Red)
                    .background(if (isWorkActive) Color(0xFFb71334) else Color(0xFF70a489))
                    .clickable {
                        vibrator.vibrate(
                            VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
                        )
                        if (isWorkActive) {
                            workManager.cancelUniqueWork("FetchRideData_$tileLabel")
                        } else {
                            val workRequest = PeriodicWorkRequestBuilder<FetchRideDataWorker>(5, TimeUnit.MINUTES)
                                .setInputData(workDataOf("rideLabel" to tileLabel))
                                .build()
                            workManager.enqueueUniquePeriodicWork(
                                "FetchRideData_$tileLabel",
                                ExistingPeriodicWorkPolicy.REPLACE,
                                workRequest
                            )
                        }
                    })
                    {
                Image(
                    painter = painterResource(id = R.drawable.bell),
                    contentDescription = "Subscription",
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(24.dp)),
                    contentScale = ContentScale.Crop
                )
            }
        }
    }
}

@Composable
fun SortingOptionsScreen() {
    var isFlipped by remember { mutableStateOf(false) }
    val rotation = remember { Animatable(0f) }
    val context = LocalContext.current
    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    LaunchedEffect(isFlipped) {
        if (isFlipped) {
            rotation.animateTo(
                targetValue = 180f,
                animationSpec = tween(
                    durationMillis = 1000,
                    easing = LinearOutSlowInEasing
                )
            )
        } else {
            rotation.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = 1000,
                    easing = LinearOutSlowInEasing
                )
            )
        }
    }


    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.subscription),
            contentDescription = "Subscription",
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(24.dp)),
            contentScale = ContentScale.Crop
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(horizontal = 50.dp)
                .padding(top = 30.dp)
                .padding(bottom = 70.dp)
                .graphicsLayer {
                    rotationY = rotation.value
                    cameraDistance = 8 * density
                }
                .clickable {
                    isFlipped = !isFlipped
                    vibrator.vibrate(
                        VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
                    )
                },
            contentAlignment = Alignment.TopCenter
        ) {
            val imageId = if (rotation.value <= 90f) R.drawable.face else R.drawable.qr

            Image(
                painter = painterResource(id = imageId),
                contentDescription = "Face",
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(24.dp)),
                contentScale = ContentScale.Crop
            )
        }
    }
}

suspend fun fetchJsonFromUrl(): JsonObject? {
    val url = "https://queue-times.com/parks/160/queue_times.json"
    val client = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 15000
            connectTimeoutMillis = 15000
            socketTimeoutMillis = 15000
        }
    }

    return try {
        val response: HttpResponse = client.get(url)
        if (response.status == HttpStatusCode.OK) {
            val responseBody: String = response.bodyAsText()
            Json.parseToJsonElement(responseBody).jsonObject
        } else {
            null
        }
    } catch (e: Exception) {
        Log.e("fetchJsonFromUrl", "Error fetching data", e)
        null
    } finally {
        client.close()
    }
}

fun JsonObject.getAllRides(excludedIds: List<Int>): Pair<List<TileItem>, Map<String, List<TileItem>>> {
    val rides = mutableListOf<TileItem>()
    val rideMap = mutableMapOf<String, MutableList<TileItem>>()
    val lands = this["lands"]?.jsonArray ?: return rides to rideMap

    for (land in lands) {
        val ridesArray = land.jsonObject["rides"]?.jsonArray ?: continue
        for (ride in ridesArray) {
            val rideObject = ride.jsonObject
            val rideId = rideObject["id"]?.jsonPrimitive?.int ?: 0
            val rideName = rideObject["name"]?.jsonPrimitive?.content ?: "Unknown Ride"
            val waitTime = if (rideObject["is_open"]?.jsonPrimitive?.boolean == true) {
                rideObject["wait_time"]?.jsonPrimitive?.intOrNull ?: 0
            } else {
                1000
            }

            if (rideId !in excludedIds) {
                val tileItem = TileItem(
                    id = rideId,
                    label = rideName,
                    waitTime = waitTime
                )
                rides.add(tileItem)
            }
        }
    }

    // Filter out rides with longer names that contain the shorter names
    val filteredRides = mutableListOf<TileItem>()
    for (ride in rides) {
        if (rides.none { it.label != ride.label && ride.label.contains(it.label) }) {
            filteredRides.add(ride)
        } else {
            val shorterName = rides.find { it.label != ride.label && ride.label.contains(it.label) }?.label
            if (shorterName != null) {
                if (!rideMap.containsKey(shorterName)) {
                    rideMap[shorterName] = mutableListOf()
                }
                rideMap[shorterName]?.add(ride)
            }
        }
    }

    return filteredRides to rideMap
}

fun JsonObject.getWaitTimeForRide(rideName: String): Int? {
    val lands = this["lands"]?.jsonArray ?: return null
    for (land in lands) {
        val rides = land.jsonObject["rides"]?.jsonArray ?: continue
        for (ride in rides) {
            if (ride.jsonObject["name"]?.jsonPrimitive?.content == rideName) {
                if (ride.jsonObject["is_open"]?.jsonPrimitive?.content == "true") {
                    return ride.jsonObject["wait_time"]?.jsonPrimitive?.intOrNull
                } else {
                    return 1000
                }
            }
        }
    }
    return null
}

@Preview(device = Devices.WEAR_OS_SMALL_ROUND, showSystemUi = true)
@Composable
fun DefaultPreview() {
    val navController = rememberNavController()
    WearApp(navController)
}
