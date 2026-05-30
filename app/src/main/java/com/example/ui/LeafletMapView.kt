package com.example.ui

import android.annotation.SuppressLint
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.RoutePoint

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LeafletMapView(
    modifier: Modifier = Modifier,
    viewModel: RoadTrackerViewModel,
    userLocation: RoutePoint?
) {
    val routes by viewModel.allRoutes.collectAsStateWithLifecycle()
    val activeCoordinates by viewModel.activeCoordinates.collectAsStateWithLifecycle()
    val drawingPoints by viewModel.drawingPoints.collectAsStateWithLifecycle()
    val drawingWaypoints by viewModel.drawingWaypoints.collectAsStateWithLifecycle()
    val isDrawingMode by viewModel.isDrawingMode.collectAsStateWithLifecycle()
    val mapCenter by viewModel.mapCenter.collectAsStateWithLifecycle()
    val searchMarker by viewModel.searchMarker.collectAsStateWithLifecycle()
    val isLightMap by viewModel.isLightMap.collectAsStateWithLifecycle()
    val isMapBlackedOut by viewModel.isMapBlackedOut.collectAsStateWithLifecycle()

    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var isPageFinished by remember { mutableStateOf(false) }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        isPageFinished = true
                        
                        // Push initial states upon loading safely from state values
                        evaluateJavascript("setMapTheme(${viewModel.isLightMap.value})", null)

                        val currentRoutes = viewModel.allRoutes.value
                        val routesJson = JsonHelper.routesToJson(currentRoutes)
                        evaluateJavascript("drawSavedRoutes($routesJson)", null)
                        
                        evaluateJavascript("setDrawingMode(${viewModel.isDrawingMode.value})", null)
                        
                        val activeCoors = viewModel.activeCoordinates.value
                        if (activeCoors.isNotEmpty()) {
                            val activeJson = JsonHelper.pointsToJson(activeCoors)
                            evaluateJavascript("updateCurrentRide($activeJson)", null)
                        }
                        
                        val drPoints = viewModel.drawingPoints.value
                        if (drPoints.isNotEmpty()) {
                            val drawingJson = JsonHelper.pointsToJson(drPoints)
                            val waypointsJson = JsonHelper.pointsToJson(viewModel.drawingWaypoints.value)
                            evaluateJavascript("updateDrawing($drawingJson, $waypointsJson)", null)
                        }
                        
                        userLocation?.let {
                            evaluateJavascript("updateUserLocation(${it.lat}, ${it.lng})", null)
                            evaluateJavascript("centerMap(${it.lat}, ${it.lng}, 14)", null)
                        }

                        val currentSearchMarker = viewModel.searchMarker.value
                        if (currentSearchMarker != null) {
                            val escapedName = JsonHelper.stringToJson(currentSearchMarker.name)
                            evaluateJavascript("updateSearchResultMarker(${currentSearchMarker.lat}, ${currentSearchMarker.lng}, $escapedName)", null)
                        }
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        android.util.Log.d("LeafletWebView", consoleMessage?.message() ?: "")
                        return true
                    }
                }

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = true
                    allowContentAccess = true
                    mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    allowFileAccessFromFileURLs = true
                    allowUniversalAccessFromFileURLs = true
                }

                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onMapClick(lat: Double, lng: Double) {
                        post {
                            viewModel.addDrawingPoint(RoutePoint(lat, lng))
                        }
                    }

                    @JavascriptInterface
                    fun onMarkerClick(index: Int) {
                        post {
                            viewModel.removeDrawingPoint(index)
                        }
                    }

                    @JavascriptInterface
                    fun onRouteSelect(routeId: String) {
                        post {
                            val route = viewModel.allRoutes.value.find { it.id == routeId }
                            viewModel.selectRoute(route)
                        }
                    }
                }, "AndroidInterface")

                loadUrl("file:///android_asset/map.html")
                webViewInstance = this
            }
        },
        update = { webView ->
            webView.visibility = if (isMapBlackedOut) android.view.View.INVISIBLE else android.view.View.VISIBLE
        }
    )

    // Keep state updates in sync
    LaunchedEffect(routes, isPageFinished) {
        if (isPageFinished && webViewInstance != null) {
            val json = JsonHelper.routesToJson(routes)
            webViewInstance?.evaluateJavascript("drawSavedRoutes($json)", null)
        }
    }

    LaunchedEffect(activeCoordinates, isPageFinished, isMapBlackedOut) {
        if (isPageFinished && webViewInstance != null && !isMapBlackedOut) {
            val json = JsonHelper.pointsToJson(activeCoordinates)
            webViewInstance?.evaluateJavascript("updateCurrentRide($json)", null)
        }
    }

    LaunchedEffect(drawingPoints, drawingWaypoints, isPageFinished, isMapBlackedOut) {
        if (isPageFinished && webViewInstance != null && !isMapBlackedOut) {
            val drawingJson = JsonHelper.pointsToJson(drawingPoints)
            val waypointsJson = JsonHelper.pointsToJson(drawingWaypoints)
            webViewInstance?.evaluateJavascript("updateDrawing($drawingJson, $waypointsJson)", null)
        }
    }

    LaunchedEffect(isDrawingMode, isPageFinished, isMapBlackedOut) {
        if (isPageFinished && webViewInstance != null && !isMapBlackedOut) {
            webViewInstance?.evaluateJavascript("setDrawingMode($isDrawingMode)", null)
        }
    }

    LaunchedEffect(isLightMap, isPageFinished, isMapBlackedOut) {
        if (isPageFinished && webViewInstance != null && !isMapBlackedOut) {
            webViewInstance?.evaluateJavascript("setMapTheme($isLightMap)", null)
        }
    }

    LaunchedEffect(userLocation, isPageFinished, isMapBlackedOut) {
        if (isPageFinished && webViewInstance != null && userLocation != null && !isMapBlackedOut) {
            webViewInstance?.evaluateJavascript("updateUserLocation(${userLocation.lat}, ${userLocation.lng})", null)
        }
    }

    LaunchedEffect(mapCenter, isPageFinished) {
        val center = mapCenter
        if (isPageFinished && webViewInstance != null && center != null) {
            webViewInstance?.evaluateJavascript("centerMap(${center.lat}, ${center.lng}, 15)", null)
            viewModel.clearMapCenterTrigger()
        }
    }

    LaunchedEffect(searchMarker, isPageFinished) {
        if (isPageFinished && webViewInstance != null) {
            val marker = searchMarker
            if (marker != null) {
                val escapedName = JsonHelper.stringToJson(marker.name)
                webViewInstance?.evaluateJavascript("updateSearchResultMarker(${marker.lat}, ${marker.lng}, $escapedName)", null)
            } else {
                webViewInstance?.evaluateJavascript("updateSearchResultMarker(null, null, null)", null)
            }
        }
    }

    // Wakeup repaint behavior: refresh all mapping parameters once the screen is awake
    LaunchedEffect(isMapBlackedOut) {
        if (!isMapBlackedOut && isPageFinished && webViewInstance != null) {
            // Repaint active coordinate path
            val activeCoors = viewModel.activeCoordinates.value
            val activeJson = JsonHelper.pointsToJson(activeCoors)
            webViewInstance?.evaluateJavascript("updateCurrentRide($activeJson)", null)
            
            // Re-focus user position
            userLocation?.let {
                webViewInstance?.evaluateJavascript("updateUserLocation(${it.lat}, ${it.lng})", null)
                webViewInstance?.evaluateJavascript("centerMap(${it.lat}, ${it.lng}, 14)", null)
            }

            // Restore search markers
            val marker = viewModel.searchMarker.value
            if (marker != null) {
                val escapedName = JsonHelper.stringToJson(marker.name)
                webViewInstance?.evaluateJavascript("updateSearchResultMarker(${marker.lat}, ${marker.lng}, $escapedName)", null)
            }
        }
    }
}
