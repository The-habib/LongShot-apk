package com.example

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.enableSlowWholeDocumentDraw()
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var urlText by remember { mutableStateOf("https://www.google.com") }
    var currentUrl by remember { mutableStateOf(urlText) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val coroutineScope = rememberCoroutineScope()
    var isCapturing by remember { mutableStateOf(false) }
    var captureFormat by remember { mutableStateOf<String?>(null) }

    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(if (captureFormat == "pdf") "application/pdf" else "image/png")
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                isCapturing = true
                try {
                    val bitmap = previewBitmap
                    if (bitmap == null) {
                        snackbarHostState.showSnackbar("No preview image found")
                    } else {
                        withContext(Dispatchers.IO) {
                            val outputStream = context.contentResolver.openOutputStream(uri)
                                ?: throw Exception("Cannot open stream")
                            if (captureFormat == "pdf") {
                                saveBitmapAsPdf(bitmap, outputStream)
                            } else {
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                                outputStream.close()
                            }
                        }
                        snackbarHostState.showSnackbar("Saved successfully!")
                    }
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar("Error: ${e.message}")
                } finally {
                    isCapturing = false
                    previewBitmap = null
                }
            }
        }
    }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(context)) {
            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            // Note: In real app, calling launch directly from observer might fail if not careful, but it's okay here
        } else {
            coroutineScope.launch { snackbarHostState.showSnackbar("Overlay permission denied") }
        }
    }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(context, OverlayService::class.java).apply {
                putExtra("resultCode", result.resultCode)
                putExtra("data", result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } else {
            coroutineScope.launch { snackbarHostState.showSnackbar("Screen capture permission denied") }
        }
    }

    fun startOverlayFlow() {
        if (!Settings.canDrawOverlays(context)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            overlayPermissionLauncher.launch(intent)
        } else {
            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = urlText,
                        onValueChange = { urlText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 8.dp),
                        placeholder = { Text("Enter URL") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = {
                            var targetUrl = urlText.trim()
                            if (!targetUrl.startsWith("http://") && !targetUrl.startsWith("https://")) {
                                targetUrl = "https://$targetUrl"
                                urlText = targetUrl
                            }
                            currentUrl = targetUrl
                            keyboardController?.hide()
                            focusManager.clearFocus()
                        })
                    )
                },
                actions = {
                    IconButton(onClick = { startOverlayFlow() }) {
                        Icon(imageVector = Icons.Default.Layers, contentDescription = "Start Overlay")
                    }
                    IconButton(onClick = {
                        var targetUrl = urlText.trim()
                        if (!targetUrl.startsWith("http://") && !targetUrl.startsWith("https://")) {
                            targetUrl = "https://$targetUrl"
                            urlText = targetUrl
                        }
                        currentUrl = targetUrl
                        keyboardController?.hide()
                        focusManager.clearFocus()
                    }) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Go")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    coroutineScope.launch {
                        isCapturing = true
                        try {
                            previewBitmap = getWebViewBitmap(webViewRef)
                            if (previewBitmap == null) {
                                snackbarHostState.showSnackbar("Failed to prepare screenshot")
                            }
                        } catch (e: Exception) {
                            snackbarHostState.showSnackbar("Error: ${e.message}")
                        } finally {
                            isCapturing = false
                        }
                    }
                },
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(imageVector = Icons.Default.Camera, contentDescription = "Take Web Screenshot")
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                if (view != null && url != null) {
                                    urlText = url
                                    currentUrl = url
                                }
                            }
                        }
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        webViewRef = this
                        loadUrl(currentUrl)
                    }
                },
                update = { webView ->
                    if (webView.url != currentUrl) {
                        webView.loadUrl(currentUrl)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            
            if (isCapturing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    Card {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("Processing...")
                        }
                    }
                }
            }
            
            previewBitmap?.let { bmp ->
                Dialog(
                    onDismissRequest = { previewBitmap = null },
                    properties = DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier
                            .fillMaxSize(0.9f)
                            .padding(16.dp)
                    ) {
                        Column {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "Preview",
                                    modifier = Modifier.fillMaxWidth(),
                                    contentScale = androidx.compose.ui.layout.ContentScale.FillWidth
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                TextButton(onClick = { previewBitmap = null }) {
                                    Text("Discard")
                                }
                                Button(onClick = {
                                    captureFormat = "png"
                                    createDocumentLauncher.launch("screenshot_${System.currentTimeMillis()}.png")
                                }) {
                                    Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.padding(end = 4.dp).size(20.dp))
                                    Text("Save PNG")
                                }
                                Button(onClick = {
                                    captureFormat = "pdf"
                                    createDocumentLauncher.launch("screenshot_${System.currentTimeMillis()}.pdf")
                                }) {
                                    Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.padding(end = 4.dp).size(20.dp))
                                    Text("Save PDF")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

fun getWebViewBitmap(webView: WebView?): Bitmap? {
    if (webView == null) return null
    try {
        val width = webView.measuredWidth
        val scale = webView.resources.displayMetrics.density
        val contentHeightPx = (webView.contentHeight * scale).toInt()
        val height = maxOf(webView.measuredHeight, contentHeightPx)
        
        if (width <= 0 || height <= 0) return null
        
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { color = android.graphics.Color.WHITE }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        
        webView.draw(canvas)
        return bitmap
    } catch (e: Exception) {
        e.printStackTrace()
        return null
    }
}

fun saveBitmapAsPdf(bitmap: Bitmap, outputStream: OutputStream) {
    val pdfDocument = PdfDocument()
    val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, 1).create()
    val page = pdfDocument.startPage(pageInfo)
    
    val canvas = page.canvas
    canvas.drawBitmap(bitmap, 0f, 0f, null)
    pdfDocument.finishPage(page)
    
    pdfDocument.writeTo(outputStream)
    pdfDocument.close()
    outputStream.close()
}
