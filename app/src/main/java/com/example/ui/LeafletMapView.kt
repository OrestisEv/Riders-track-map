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
    val isDrawingMode by viewModel.isDrawingMode.collectAsStateWithLifecycle()
    val mapCenter by viewModel.mapCenter.collectAsStateWithLifecycle()

    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var isPageFinished by remember { mutableStateOf(false) }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        isPageFinished = true
                        
                        // Push initial states upon loading
                        val routesJson = JsonHelper.routesToJson(routes)
                        evaluateJavascript("drawSavedRoutes('$routesJson')", null)
                        
                        evaluateJavascript("setDrawingMode($isDrawingMode)", null)
                        
                        if (activeCoordinates.isNotEmpty()) {
                            val activeJson = JsonHelper.pointsToJson(activeCoordinates)
                            evaluateJavascript("updateCurrentRide('$activeJson')", null)
                        }
                        
                        if (drawingPoints.isNotEmpty()) {
                            val drawingJson = JsonHelper.pointsToJson(drawingPoints)
                            evaluateJavascript("updateDrawingPoints('$drawingJson')", null)
                        }
                        
                        userLocation?.let {
                            evaluateJavascript("updateUserLocation(${it.lat}, ${it.lng})", null)
                            evaluateJavascript("centerMap(${it.lat}, ${it.lng}, 14)", null)
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
                            val route = routes.find { it.id == routeId }
                            viewModel.selectRoute(route)
                        }
                    }
                }, "AndroidInterface")

                loadUrl("file:///android_asset/map.html")
                webViewInstance = this
            }
        },
        update = {
            // Nothing needed here since JavaScript evaluation handles incremental updates
        }
    )

    // Keep state updates in sync
    LaunchedEffect(routes, isPageFinished) {
        if (isPageFinished && webViewInstance != null) {
            val json = JsonHelper.routesToJson(routes)
            webViewInstance?.evaluateJavascript("drawSavedRoutes('$json')", null)
        }
    }

    LaunchedEffect(activeCoordinates, isPageFinished) {
        if (isPageFinished && webViewInstance != null) {
            val json = JsonHelper.pointsToJson(activeCoordinates)
            webViewInstance?.evaluateJavascript("updateCurrentRide('$json')", null)
        }
    }

    LaunchedEffect(drawingPoints, isPageFinished) {
        if (isPageFinished && webViewInstance != null) {
            val json = JsonHelper.pointsToJson(drawingPoints)
            webViewInstance?.evaluateJavascript("updateDrawingPoints('$json')", null)
        }
    }

    LaunchedEffect(isDrawingMode, isPageFinished) {
        if (isPageFinished && webViewInstance != null) {
            webViewInstance?.evaluateJavascript("setDrawingMode($isDrawingMode)", null)
        }
    }

    LaunchedEffect(userLocation, isPageFinished) {
        if (isPageFinished && webViewInstance != null && userLocation != null) {
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
}
