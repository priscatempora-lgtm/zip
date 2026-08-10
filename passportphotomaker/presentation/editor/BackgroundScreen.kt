package com.example.passportphotomaker.presentation.editor

  import android.graphics.Bitmap
  import android.graphics.BitmapFactory
  import android.graphics.Canvas
  import android.graphics.Matrix
  import android.graphics.Paint
  import android.graphics.PorterDuff
  import android.graphics.PorterDuffXfermode
  import android.graphics.Shader
  import android.graphics.BitmapShader
  import android.os.Build
  import androidx.activity.compose.rememberLauncherForActivityResult
  import androidx.activity.result.PickVisualMediaRequest
  import androidx.activity.result.contract.ActivityResultContracts
  import androidx.compose.foundation.background
  import androidx.compose.foundation.border
  import androidx.compose.foundation.clickable
  import androidx.compose.foundation.gestures.awaitEachGesture
  import androidx.compose.foundation.gestures.awaitFirstDown
  import androidx.compose.foundation.gestures.calculateCentroid
  import androidx.compose.foundation.gestures.calculatePan
  import androidx.compose.foundation.gestures.calculateZoom
  import androidx.compose.foundation.layout.*
  import androidx.compose.foundation.lazy.LazyRow
  import androidx.compose.foundation.lazy.items
  import androidx.compose.foundation.shape.CircleShape
  import androidx.compose.material.icons.Icons
  import androidx.compose.material.icons.automirrored.filled.ArrowBack
  import androidx.compose.material.icons.automirrored.filled.Redo
  import androidx.compose.material.icons.automirrored.filled.Undo
  import androidx.compose.material.icons.filled.AutoAwesome
  import androidx.compose.material.icons.filled.Check
  import androidx.compose.material.icons.filled.Close
  import androidx.compose.material.icons.filled.ContentCut
  import androidx.compose.material.icons.outlined.AutoFixNormal
  import androidx.compose.material.icons.outlined.Brush
  import androidx.compose.material3.*
  import androidx.compose.runtime.*
  import androidx.compose.ui.Alignment
  import androidx.compose.ui.Modifier
  import androidx.compose.ui.draw.clip
  import androidx.compose.ui.geometry.Offset
  import androidx.compose.ui.geometry.Size
  import androidx.compose.ui.graphics.BlendMode
  import androidx.compose.ui.graphics.Color
  import androidx.compose.ui.graphics.toArgb
  import androidx.compose.ui.graphics.CompositingStrategy
  import androidx.compose.ui.graphics.Path
  import androidx.compose.ui.graphics.ShaderBrush
  import androidx.compose.ui.graphics.StrokeCap
  import androidx.compose.ui.graphics.StrokeJoin
  import androidx.compose.ui.graphics.asAndroidPath
  import androidx.compose.ui.graphics.asImageBitmap
  import androidx.compose.ui.graphics.drawscope.Stroke
  import androidx.compose.ui.graphics.drawscope.clipRect
  import androidx.compose.ui.graphics.drawscope.translate
  import androidx.compose.ui.graphics.drawscope.withTransform
  import androidx.compose.ui.graphics.graphicsLayer
  import androidx.compose.ui.graphics.lerp
  import androidx.compose.ui.graphics.vector.ImageVector
  import androidx.compose.ui.input.pointer.pointerInput
  import androidx.compose.ui.input.pointer.positionChanged
  import androidx.compose.ui.platform.LocalContext
  import androidx.compose.ui.platform.LocalDensity
  import androidx.compose.ui.text.font.FontWeight
  import androidx.compose.ui.unit.IntOffset
  import androidx.compose.ui.unit.IntSize
  import androidx.compose.ui.unit.dp
  import com.example.passportphotomaker.domain.model.ProjectType
  import com.example.passportphotomaker.domain.util.BackgroundRemover
  import kotlinx.coroutines.Dispatchers
  import kotlinx.coroutines.delay
  import kotlinx.coroutines.launch
  import kotlinx.coroutines.withContext

  // Ã¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢Â
  //  Shared models & helpers
  // Ã¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢Â

  /** Standard passport photo blue. Tweak to your target spec if needed. */
  val PassportBlue = Color(0xFF2E75CC)

  enum class CutoutTool { ERASER, REPAIR }

  /**
   * One manual brush stroke, recorded in *unscaled image-draw coordinates*
   * (i.e. relative to the image's top-left corner at scale = 1).
   * Storing paths instead of bitmap snapshots keeps the undo stack ~free:
   * a full-res ARGB_8888 snapshot per stroke would cost 30Ã¢â‚¬â€œ50 MB each.
   */
  data class MaskStroke(
      val path: Path,
      val width: Float,        // stroke width in unscaled draw-px
      val isErase: Boolean     // true = eraser, false = repair (restore)
  )

  /** Photoshop-style checkerboard, used as a static screen background. */
  @Composable
  fun rememberCheckerboardBrush(): ShaderBrush = remember {
      val size = 40
      val bmp = Bitmap.createBitmap(size * 2, size * 2, Bitmap.Config.ARGB_8888)
      val cv = Canvas(bmp)
      val p1 = Paint().apply { color = android.graphics.Color.parseColor("#CCCCCC") }
      val p2 = Paint().apply { color = android.graphics.Color.parseColor("#FFFFFF") }
      cv.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p1)
      cv.drawRect(size.toFloat(), size.toFloat(), size * 2f, size * 2f, p1)
      cv.drawRect(size.toFloat(), 0f, size * 2f, size.toFloat(), p2)
      cv.drawRect(0f, size.toFloat(), size.toFloat(), size * 2f, p2)
      ShaderBrush(BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT))
  }

  /**
   * Bakes the base cutout + active strokes into a single new bitmap.
   * Runs on Dispatchers.Default Ã¢â‚¬â€ call from a coroutine.
   *
   * @param base              the ML cutout currently being edited (NOT recycled here)
   * @param pristineSource    the untouched original Ã¢â‚¬â€ Repair strokes always sample this
   * @param strokes           active strokes, in unscaled image-draw coordinates
   * @param imageDrawWidthPx  on-screen draw width the strokes were recorded against
   */
  suspend fun bakeCutout(
      base: Bitmap,
      pristineSource: Bitmap,
      strokes: List<MaskStroke>,
      imageDrawWidthPx: Float
  ): Bitmap = withContext(Dispatchers.Default) {
      val out = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
      val cv = Canvas(out)
      cv.drawBitmap(base, 0f, 0f, null)

      // Map on-screen draw coordinates Ã¢â€ â€™ bitmap pixel coordinates
      val toBmpScale = out.width / imageDrawWidthPx.coerceAtLeast(1f)
      val toBitmap = Matrix().apply { setScale(toBmpScale, toBmpScale) }

      // Repair shader: ALWAYS the pristine source, scaled to match the output
      // bitmap's pixel grid. Never the working bitmap Ã¢â‚¬â€ that's what caused the
      // "Restore Amnesia" bug where restore painted already-erased pixels.
      val pristineShader = BitmapShader(pristineSource, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
          if (pristineSource.width != out.width) {
              val s = out.width.toFloat() / pristineSource.width.toFloat()
              setLocalMatrix(Matrix().apply { setScale(s, s) })
          }
      }

      strokes.forEach { stroke ->
          val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
              style = Paint.Style.STROKE
              strokeWidth = stroke.width * toBmpScale
              strokeCap = Paint.Cap.ROUND
              strokeJoin = Paint.Join.ROUND
              if (stroke.isErase) {
                  xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
              } else {
                  shader = pristineShader
              }
          }
          val bmpPath = android.graphics.Path(stroke.path.asAndroidPath()).apply { transform(toBitmap) }
          cv.drawPath(bmpPath, paint)
      }
      out
  }

  // Ã¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢Â
  //  TIER 1 Ã¢â‚¬â€ Main BackgroundScreen
  //  Pure compositor: background layer + cutout on top. NO erase logic here,
  //  so BlendMode.Clear can never punch holes through the background again.
  // Ã¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢Â

  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  fun BackgroundScreen(
      viewModel: EditorViewModel,
      onNavigateBack: () -> Unit,
      onNavigateToNext: () -> Unit,
      isStandaloneEdit: Boolean = false,
      onEscapeBatchMode: (() -> Unit)? = null
  ) {
      val context = LocalContext.current
      val coroutineScope = rememberCoroutineScope()

        // ── Batch mode state (Requirement 4) ─────────────────────────────────────
        val isBatchMode      by viewModel.isBatchMode.collectAsState()
        val printBatches     by viewModel.printBatches.collectAsState()
        
        // 🔥 FIX: Freeze the badge number so it doesn't visually tick up +1 
        // during the loading screen when the image is saved to the ViewModel!
        val batchImageNumber = remember { printBatches.size + 1 }


      // Bug 3: pre-load the project's persisted image when Background is opened
      // directly from "My Studio" (standalone edit) without going through Crop/Retouch.
      LaunchedEffect(isStandaloneEdit) {
          if (!isStandaloneEdit) return@LaunchedEffect
          viewModel.loadImageForStandaloneEdit(context)
      }

            // The baked-retouch bitmap. This is our PRISTINE SOURCE for this phase.
      // It is never overwritten and never recycled by this screen.
            // 1. Monitor the dynamically changing master output
      val dynamicFinalBitmap by viewModel.finalCroppedBitmap.collectAsState()
      
      // 2. Read the protected pristine source from our vault
      val pristineSourceBitmap by viewModel.pristineBackgroundBitmap.collectAsState()

      // 3. Trap the image IMMEDIATELY on arrival. 
      // Because the ViewModel vault ignores updates if it's already full, 
      // this perfectly protects the original image even when 'bakeAndProceed' 
      // overwrites dynamicFinalBitmap with the blue background.
      LaunchedEffect(dynamicFinalBitmap) {
          dynamicFinalBitmap?.let { bmp ->
              viewModel.capturePristineBackground(bmp)
          }
      }

      // Fix 3: reuse any cutout the ViewModel persisted from a previous visit so
      // back-navigation never re-runs the heavy ML engine.
      val persistedCutout by viewModel.processedCutoutBitmap.collectAsState()

      // The current person-cutout with transparent background.
      var cutoutBitmap by remember { 
          mutableStateOf(viewModel.processedCutoutBitmap.value) 
      }
      var autoRemovedBitmap by remember { 
          mutableStateOf(viewModel.processedCutoutBitmap.value) 
      }

      var isProcessing by remember { mutableStateOf(false) }
      var showCutoutEditor by remember { mutableStateOf(false) }
      var showDpiDialog by remember { mutableStateOf(false) }
      var selectedDpi by remember { mutableStateOf(300) }

      // Guard flag: prevents the LaunchedEffect from re-triggering the heavy ML
      // model when pristineSourceBitmap changes after bakeAndProceed updates it.
      // 1. AUTO-TRIGGER: run background removal exactly once per screen visit
      // Fix 3: if the ViewModel already holds a processed cutout (user pressed
      // Back from OutputFormat), restore it immediately and skip the ML engine.
      // 1. AUTO-TRIGGER: run background removal whenever a NEW pristine source arrives!
       LaunchedEffect(pristineSourceBitmap) {
        val src = pristineSourceBitmap ?: return@LaunchedEffect
        
        // No more 'hasProcessedBg' blocking the way! 
        // We only skip the ML engine if the ViewModel explicitly hands us a saved cache.
        val cached = persistedCutout
        if (cached != null && !cached.isRecycled) {
            // Restore from ViewModel — no ML needed.
            autoRemovedBitmap = cached
            cutoutBitmap      = cached
        } else {
            try {
                isProcessing = true
                val cutout = withContext(Dispatchers.Default) {
                    BackgroundRemover.removeBackground(context, src)
                }
                autoRemovedBitmap = cutout
                cutoutBitmap      = cutout ?: src   // graceful fallback if ML fails
                
                // Persist the ML result so forward-navigation to Output can reuse it.
                if (cutout != null) viewModel.storeProcessedCutout(cutout)
            } finally {
                // GUARANTEED to execute, even if the coroutine is cancelled.
                isProcessing = false
            }
        }
    }

      // Ã¢â€â‚¬Ã¢â€â‚¬ 2. Background layer state Ã¢â‚¬â€ DEFAULT: Passport Blue Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬
      val standardColors = listOf(
          PassportBlue, Color.White, Color(0xFFE6F2FF),
          Color(0xFFF0F0F0), Color(0xFF003399), Color(0xFFCC0000), Color.Transparent
      )
      var selectedBgColor by remember { mutableStateOf(PassportBlue) }
      var bgShade by remember { mutableStateOf(0f) }
      var bgImageBitmap by remember { mutableStateOf<Bitmap?>(null) }

      val finalBgColor = remember(selectedBgColor, bgShade) {
          when {
              selectedBgColor == Color.Transparent -> Color.Transparent
              bgShade > 0f -> lerp(selectedBgColor, Color.White, bgShade)
              else -> lerp(selectedBgColor, Color.Black, -bgShade)
          }
      }

      val checkerboardBrush = rememberCheckerboardBrush()

      val photoPickerLauncher = rememberLauncherForActivityResult(
          ActivityResultContracts.PickVisualMedia()
      ) { uri ->
          uri?.let {
              bgImageBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                  android.graphics.ImageDecoder.decodeBitmap(
                      android.graphics.ImageDecoder.createSource(context.contentResolver, it)
                  ) { decoder, _, _ -> decoder.isMutableRequired = true }
              } else {
                  @Suppress("DEPRECATION")
                  context.contentResolver.openInputStream(it)?.use { s -> BitmapFactory.decodeStream(s) }
              }
          }
      }

      val selectedPreset by viewModel.selectedPreset.collectAsState()
      val customWidthMm by viewModel.customPrintWidthMm.collectAsState()
      val customHeightMm by viewModel.customPrintHeightMm.collectAsState()

      // Ã¢"â‚¬Ã¢"â‚¬ Flatten bg + cutout at the chosen DPI, then proceed Ã¢"â‚¬Ã¢"â‚¬Ã¢"â‚¬Ã¢"â‚¬Ã¢"â‚¬Ã¢"â‚¬Ã¢"â‚¬Ã¢"â‚¬Ã¢"â‚¬Ã¢"â‚¬Ã¢"â‚¬Ã¢"â‚¬Ã¢"â‚¬Ã¢"â‚¬Ã¢"â‚¬
     fun bakeAndProceed(dpi: Int) {
        if (isProcessing) return
        val cutout = cutoutBitmap ?: return
        
        // 1. MANUAL ARGB EXTRACTION ON MAIN THREAD
        val a = (finalBgColor.alpha * 255).toInt().coerceIn(0, 255)
        val r = (finalBgColor.red * 255).toInt().coerceIn(0, 255)
        val g = (finalBgColor.green * 255).toInt().coerceIn(0, 255)
        val b = (finalBgColor.blue * 255).toInt().coerceIn(0, 255)
        val bgColorInt = android.graphics.Color.argb(a, r, g, b)
        val isTransparentBg = finalBgColor == Color.Transparent
        
        val currentBgImage = bgImageBitmap
        
        // The anchor physical width is taken from the preset.
        val anchorWidthMm = customWidthMm ?: selectedPreset.widthMm
        
        isProcessing = true
        coroutineScope.launch {
            // Yield to Compose to draw the blackout overlay
            delay(150) 
            
            val finalBitmap = withContext(Dispatchers.Default) {
                val cutoutRatio = cutout.width.toFloat() / cutout.height.toFloat()
            
                // CHANGED: for standalone edits, preserve the cutout's native resolution —
                // don't downscale to a physical print size yet. Only the final Export step
                // should apply DPI-based sizing; baking small here and upscaling later at
                // Export would compound into real, visible quality loss.
                val outW = if (isStandaloneEdit) cutout.width
                        else ((anchorWidthMm / 25.4f) * dpi).toInt().coerceAtLeast(1)
                val outH = if (isStandaloneEdit) cutout.height
                        else (outW / cutoutRatio).toInt().coerceAtLeast(1)
            
                val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                out.setHasAlpha(true) 
                val cv = android.graphics.Canvas(out)
                
                // 3. FILL BACKGROUND LAYER
                if (currentBgImage != null) {
                    val bgRatio = currentBgImage.width.toFloat() / currentBgImage.height.toFloat()
                    val destRatio = outW.toFloat() / outH.toFloat()
                    var srcW = currentBgImage.width.toFloat()
                    var srcH = currentBgImage.height.toFloat()
                    if (bgRatio > destRatio) {
                        srcW = srcH * destRatio
                    } else {
                        srcH = srcW / destRatio
                    }
                    val srcX = (currentBgImage.width - srcW) / 2f
                    val srcY = (currentBgImage.height - srcH) / 2f
                    cv.drawBitmap(
                        currentBgImage,
                        android.graphics.Rect(srcX.toInt(), srcY.toInt(), (srcX + srcW).toInt(), (srcY + srcH).toInt()),
                        android.graphics.Rect(0, 0, outW, outH),
                        null
                    )
                } else if (!isTransparentBg) {
                    val paintBg = android.graphics.Paint().apply {
                        color = bgColorInt
                        style = android.graphics.Paint.Style.FILL
                    }
                    cv.drawRect(0f, 0f, outW.toFloat(), outH.toFloat(), paintBg)
                }
                
                // 4. DRAW CUTOUT (Perfectly matches outW and outH, zero squashing)
                val scaledCutout = Bitmap.createScaledBitmap(cutout, outW, outH, true)
                cv.drawBitmap(scaledCutout, 0f, 0f, null)
                if (scaledCutout !== cutout) scaledCutout.recycle()
                
                out
            }
            
            // 5. UPDATE MASTER STATE & BACKSTACK PRESERVATION
            val actualRatio = cutout.width.toFloat() / cutout.height.toFloat()
            val actualHeightMm = anchorWidthMm / actualRatio
            
            viewModel.setCroppedBitmap(finalBitmap)
            // Keep the post-background image as the editable Text source.
            // Text output is rendered into a separate temporary bitmap/file.
            viewModel.saveToCache(context, finalBitmap, "step_3_background.webp")
            
            if (isStandaloneEdit) {
                viewModel.saveStagingBitmapToPrivateFile(context)
            } else {
                val savedPath = viewModel.saveEditedBitmapToPrivateFile(context)
                if (savedPath != null) {
                    viewModel.ensureCurrentBitmapInBatches(savedPath, anchorWidthMm, actualHeightMm, actualRatio)
                }
            }
            
            // REMOVED: viewModel.updateProjectType(ProjectType.PRINT) used to run here
            // unconditionally, tagging the anchor project as PRINT before the user had
            // even chosen Digital vs Print on OutputSelectionScreen — creating a phantom
            // History card. Now that saveDigitalCopy/print-export each mint their own
            // dedicated card (Option B), the anchor project should stay untagged
            // (projectType = UNKNOWN, invisible in History) until one of those actually
            // fires with the user's real choice.
            
            onNavigateToNext()
            delay(500)
            isProcessing = false
        }
    }

      // Ã¢â€â‚¬Ã¢â€â‚¬ TIER 2 takes over the whole screen when active Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬
      val pristine = pristineSourceBitmap
      val currentCutout = cutoutBitmap
      if (showCutoutEditor && pristine != null && currentCutout != null) {
            CutoutEditorScreen(
                pristineSourceBitmap = pristine,
                initialCutout = currentCutout,
                cachedAutoRemovedBitmap = autoRemovedBitmap,
                onCacheAutoRemoved = { autoRemovedBitmap = it },
                onDone = { newCutout ->
                    val old = cutoutBitmap
                    cutoutBitmap = newCutout
                    
                    // 🔥 FIX: Save manual brush edits so they survive going to the Output screen!
                    viewModel.storeProcessedCutout(newCutout) 
                    
                    if (old != null && old !== newCutout &&
                        old !== autoRemovedBitmap && old !== pristineSourceBitmap
                    ) old.recycle()
                    showCutoutEditor = false
                },
                onCancel = { showCutoutEditor = false }
            )
            return
        }

      // Ã¢â€â‚¬Ã¢â€â‚¬ TIER 1 UI Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬
      Scaffold(
          topBar = {
              TopAppBar(
                  title = {
                      if (isBatchMode) {
                          Row(verticalAlignment = Alignment.CenterVertically) {
                              Text("Background")
                              Spacer(Modifier.width(6.dp))
                              Surface(
                                  shape = MaterialTheme.shapes.extraLarge,
                                  color = MaterialTheme.colorScheme.errorContainer
                              ) {
                                  Text(
                                      text     = "Batch: Image $batchImageNumber",
                                      modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                      style    = MaterialTheme.typography.labelSmall,
                                      color    = MaterialTheme.colorScheme.onErrorContainer,
                                      fontWeight = FontWeight.Bold
                                  )
                              }
                          }
                      } else { Text("Background") }
                  },
                  navigationIcon = {
                      IconButton(onClick = onNavigateBack) {
                          Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                      }
                  },
                  actions = {
                      // Batch escape hatch (Requirement 4)
                      if (isBatchMode) {
                          IconButton(onClick = { onEscapeBatchMode?.invoke() }) {
                              Icon(Icons.Filled.Close, contentDescription = "Cancel batch")
                          }
                      }
                      TextButton(
                         onClick = {
                             if (isStandaloneEdit) {
                                 bakeAndProceed(300)
                              } else {
                                showDpiDialog = true
                            }
                        },
                        enabled = !isProcessing && cutoutBitmap != null
                    ) {
                        Text("Next", fontWeight = FontWeight.Bold)
                     }
                  }
              )
          },
          bottomBar = {
              Surface(shadowElevation = 16.dp, color = MaterialTheme.colorScheme.surfaceVariant) {
                  Column(
                      modifier = Modifier
                          .fillMaxWidth()
                          .padding(16.dp)
                          .navigationBarsPadding()
                  ) {
                      // Prominent "Edit Cutout" entry point
                      FilledTonalButton(
                          onClick = { showCutoutEditor = true },
                          enabled = !isProcessing && cutoutBitmap != null,
                          modifier = Modifier.fillMaxWidth()
                      ) {
                          Icon(Icons.Filled.ContentCut, contentDescription = null, modifier = Modifier.size(18.dp))
                          Spacer(Modifier.width(8.dp))
                          Text("Edit Cutout", fontWeight = FontWeight.SemiBold)
                      }
                      Spacer(Modifier.height(12.dp))

                      // Solid colors + gallery picker
                      LazyRow(
                          horizontalArrangement = Arrangement.spacedBy(8.dp),
                          verticalAlignment = Alignment.CenterVertically
                      ) {
                          item {
                              AssistChip(
                                  onClick = {
                                      photoPickerLauncher.launch(
                                          PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                      )
                                  },
                                  label = { Text("Gallery Image") }
                              )
                          }
                          items(standardColors) { c ->
                              val isSelected = selectedBgColor == c && bgImageBitmap == null
                              Box(
                                  modifier = Modifier
                                      .size(36.dp)
                                      .clip(CircleShape)
                                      .background(if (c == Color.Transparent) Color.LightGray else c)
                                      .border(
                                          width = if (isSelected) 3.dp else 1.dp,
                                          color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray,
                                          shape = CircleShape
                                      )
                                      .clickable {
                                          selectedBgColor = c
                                          bgImageBitmap = null
                                      },
                                  contentAlignment = Alignment.Center
                              ) {
                                  if (c == Color.Transparent) {
                                      Text("x", fontWeight = FontWeight.Bold, color = Color.DarkGray)
                                  }
                              }
                          }
                      }
                      Spacer(Modifier.height(8.dp))
                      BgSliderRow(
                          label = if (bgShade >= 0f) "Lighten ${(bgShade * 100).toInt()}%" else "Darken ${(-bgShade * 100).toInt()}%",
                          value = bgShade, range = -1f..1f,
                          onValueChange = { bgShade = it }
                      )
                  }
              }
          }
          ) { paddingValues ->
          Box(
              modifier = Modifier
                  .fillMaxSize()
                  .padding(paddingValues)
                  .background(MaterialTheme.colorScheme.surface),
              contentAlignment = Alignment.Center
          ) {
              if (isProcessing) {
                  // FULL SCREEN OPAQUE OVERLAY - Unmounts canvas completely to stop flickering
                  Box(
                      modifier = Modifier
                          .fillMaxSize()
                          .background(Color.Black.copy(alpha = 0.9f)),
                      contentAlignment = Alignment.Center
                  ) {
                      Column(horizontalAlignment = Alignment.CenterHorizontally) {
                          CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                          Spacer(Modifier.height(16.dp))
                          Text(
                              text = "Processing high-resolution image...\nPlease do not close the app.",
                              color = Color.White,
                              style = MaterialTheme.typography.titleMedium,
                              textAlign = androidx.compose.ui.text.style.TextAlign.Center
                          )
                      }
                  }
              } else {
                  // ONLY draw the image canvas if NOT processing.
                  val display = cutoutBitmap
                  if (display != null) {
                      BoxWithConstraints(
                          modifier = Modifier
                              .fillMaxSize()
                              .then(
                                  if (bgImageBitmap == null && finalBgColor == Color.Transparent)
                                      Modifier.background(checkerboardBrush)
                                  else Modifier
                              )
                      ) {
                          val density = LocalDensity.current
                          val screenWidthPx = with(density) { maxWidth.toPx() }
                          val screenHeightPx = with(density) { maxHeight.toPx() }
                          val imgRatio = display.width.toFloat() / display.height.toFloat()
                          val boundsRatio = screenWidthPx / screenHeightPx
                          val drawW = if (imgRatio > boundsRatio) screenHeightPx * imgRatio else screenWidthPx
                          val drawH = if (imgRatio > boundsRatio) screenHeightPx else screenWidthPx / imgRatio
                          val drawLeft = (screenWidthPx - drawW) / 2f
                          val drawTop = (screenHeightPx - drawH) / 2f

                          androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                              clipRect(drawLeft, drawTop, drawLeft + drawW, drawTop + drawH) {
                                  bgImageBitmap?.let { bg ->
                                      val bgRatio = bg.width.toFloat() / bg.height.toFloat()
                                      val destRatio = drawW / drawH
                                      var srcW = bg.width.toFloat(); var srcH = bg.height.toFloat()
                                      if (bgRatio > destRatio) srcW = srcH * destRatio else srcH = srcW / destRatio
                                      val srcX = (bg.width - srcW) / 2f
                                      val srcY = (bg.height - srcH) / 2f
                                      drawImage(
                                          image = bg.asImageBitmap(),
                                          srcOffset = IntOffset(srcX.toInt(), srcY.toInt()),
                                          srcSize = IntSize(srcW.toInt(), srcH.toInt()),
                                          dstOffset = IntOffset(drawLeft.toInt(), drawTop.toInt()),
                                          dstSize = IntSize(drawW.toInt(), drawH.toInt())
                                      )
                                  }
                                  if (bgImageBitmap == null && finalBgColor != Color.Transparent) {
                                      drawRect(
                                          color = finalBgColor,
                                          topLeft = Offset(drawLeft, drawTop),
                                          size = Size(drawW, drawH)
                                      )
                                  }
                                  drawImage(
                                      image = display.asImageBitmap(),
                                      dstOffset = IntOffset(drawLeft.toInt(), drawTop.toInt()),
                                      dstSize = IntSize(drawW.toInt(), drawH.toInt())
                                  )
                              }
                          }
                      }
                  }
              }
          }
      }

      // ── DPI Selection Dialog ──
      if (showDpiDialog) {
          val dpiOptions = listOf(300 to "300 DPI", 350 to "350 DPI", 450 to "450 DPI", 600 to "600 DPI", 1080 to "HD (1080p)")
          AlertDialog(
              onDismissRequest = { showDpiDialog = false },
              title = { Text("Select Output Resolution") },
              text = {
                  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                      Text(
                          "Higher DPI produces a sharper print but a larger file.",
                          style = MaterialTheme.typography.bodySmall
                      )
                      Spacer(Modifier.height(4.dp))
                      dpiOptions.forEach { (dpi, label) ->
                          FilterChip(
                              selected = selectedDpi == dpi,
                              onClick = { selectedDpi = dpi },
                              label = { Text(label) },
                              modifier = Modifier.fillMaxWidth()
                          )
                      }
                  }
              },
              confirmButton = {
                  TextButton(
                      enabled = !isProcessing,
                      onClick = {
                          showDpiDialog = false
                          bakeAndProceed(selectedDpi)
                      }
                  ) {
                      Text("Continue", fontWeight = FontWeight.Bold)
                  }
              },
              dismissButton = {
                  TextButton(onClick = { showDpiDialog = false }) {
                      Text("Cancel")
                  }
              }
          )
      }
  }
  
  // Ã¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢Â
  //  TIER 2 Ã¢â‚¬â€ CutoutEditorScreen
  //  Full-screen mask editor over a checkerboard. AI / Eraser / Repair tools,
  //  path-based Undo/Redo, pristine-source repair brush, correct zoom math.
  // Ã¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢Â

  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  fun CutoutEditorScreen(
      pristineSourceBitmap: Bitmap,      // NEVER modified, NEVER recycled here
      initialCutout: Bitmap,
      cachedAutoRemovedBitmap: Bitmap?,
      onCacheAutoRemoved: (Bitmap) -> Unit,
      onDone: (Bitmap) -> Unit,
      onCancel: () -> Unit
  ) {
      val context = LocalContext.current
      val coroutineScope = rememberCoroutineScope()
      val checkerboardBrush = rememberCheckerboardBrush()

      // Base layer the strokes replay on top of. Only ever points at
      // initialCutout or the cached AI result Ã¢â‚¬â€ never recycled inside the editor.
      var baseBitmap by remember { mutableStateOf(initialCutout) }

      // Ã¢â€â‚¬Ã¢â€â‚¬ Path-based Undo/Redo stacks (memory safe: no bitmap snapshots) Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬
      var undoList by remember { mutableStateOf(listOf<MaskStroke>()) }
      var redoList by remember { mutableStateOf(listOf<MaskStroke>()) }

      var tool by remember { mutableStateOf(CutoutTool.ERASER) }
      var brushSize by remember { mutableStateOf(50f) }    // dp
      var brushOffset by remember { mutableStateOf(60f) }  // dp Ã¢â‚¬â€ draw above finger
      var activeParam by remember { mutableStateOf(0) }
      var isSliderActive by remember { mutableStateOf(false) }
      var isProcessing by remember { mutableStateOf(false) }

      // Ã¢â€â‚¬Ã¢â€â‚¬ Pan & Zoom Ã¢â‚¬â€ always available Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬
      var scale by remember { mutableStateOf(1f) }
      var pan by remember { mutableStateOf(Offset.Zero) }

      var currentPath by remember { mutableStateOf<Path?>(null) }
      var currentTouchPos by remember { mutableStateOf<Offset?>(null) }
      var imageDrawWidthPx by remember { mutableStateOf(1f) }

      fun clearStrokes() {
          undoList = emptyList()
          redoList = emptyList()
          currentPath = null
      }

      // AI button: use the cache if we have it Ã¢â‚¬â€ never re-run the ML model.
      fun onAiPressed() {
        if (cachedAutoRemovedBitmap != null) {
            baseBitmap = cachedAutoRemovedBitmap
            clearStrokes()
            return
        }
        isProcessing = true
        coroutineScope.launch {
            val cutout = withContext(Dispatchers.Default) {
                BackgroundRemover.removeBackground(context, pristineSourceBitmap)   // was: (pristineSourceBitmap)
            }
              if (cutout != null) {
                  onCacheAutoRemoved(cutout)   // parent caches it for next time
                  baseBitmap = cutout
                  clearStrokes()
              }
              isProcessing = false
          }
      }

      // Done: bake base + strokes into one bitmap on a background thread.
      fun onDonePressed() {
          if (undoList.isEmpty()) {
              onDone(baseBitmap)   // nothing to bake (possibly just an AI switch)
              return
          }
          isProcessing = true
          coroutineScope.launch {
              val baked = bakeCutout(
                  base = baseBitmap,
                  pristineSource = pristineSourceBitmap,
                  strokes = undoList,
                  imageDrawWidthPx = imageDrawWidthPx
              )
              clearStrokes()
              isProcessing = false
              onDone(baked)
          }
      }

      Scaffold(
          topBar = {
              TopAppBar(
                  title = { Text("Edit Cutout") },
                  navigationIcon = {
                      IconButton(onClick = { clearStrokes(); onCancel() }) {
                          Icon(Icons.Filled.Close, contentDescription = "Cancel")
                      }
                  },
                  actions = {
                      IconButton(
                          onClick = {
                              // Undo: move last stroke from undoList Ã¢â€ â€™ redoList
                              val last = undoList.lastOrNull() ?: return@IconButton
                              undoList = undoList.dropLast(1)
                              redoList = redoList + last
                          },
                          enabled = undoList.isNotEmpty() && !isProcessing
                      ) { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo") }

                      IconButton(
                          onClick = {
                              // Redo: move last stroke from redoList Ã¢â€ â€™ undoList
                              val last = redoList.lastOrNull() ?: return@IconButton
                              redoList = redoList.dropLast(1)
                              undoList = undoList + last
                          },
                          enabled = redoList.isNotEmpty() && !isProcessing
                      ) { Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo") }

                      IconButton(onClick = { onDonePressed() }, enabled = !isProcessing) {
                          Icon(Icons.Filled.Check, contentDescription = "Done", tint = MaterialTheme.colorScheme.primary)
                      }
                  }
              )
          },
          bottomBar = {
              Surface(shadowElevation = 16.dp, color = MaterialTheme.colorScheme.surface) {
                  Column(
                      modifier = Modifier
                          .fillMaxWidth()
                          .padding(16.dp)
                          .navigationBarsPadding()
                  ) {
                      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                          CutoutToolButton(
                              icon = Icons.Filled.AutoAwesome, label = "AI",
                              selected = false, enabled = !isProcessing,
                              onClick = { onAiPressed() }
                          )
                          CutoutToolButton(
                              icon = Icons.Outlined.AutoFixNormal, label = "Eraser",
                              selected = tool == CutoutTool.ERASER, enabled = !isProcessing,
                              onClick = { tool = CutoutTool.ERASER }
                          )
                          CutoutToolButton(
                              icon = Icons.Outlined.Brush, label = "Repair",
                              selected = tool == CutoutTool.REPAIR, enabled = !isProcessing,
                              onClick = { tool = CutoutTool.REPAIR }
                          )
                      }
                      Spacer(Modifier.height(8.dp))
                      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                          FilterChip(selected = activeParam == 0, onClick = { activeParam = 0 }, label = { Text("Brush Size") })
                          FilterChip(selected = activeParam == 1, onClick = { activeParam = 1 }, label = { Text("Brush Offset") })
                      }
                      when (activeParam) {
                          0 -> BgSliderRow("Size", brushSize, 10f..300f, { brushSize = it; isSliderActive = true }, { isSliderActive = false })
                          1 -> BgSliderRow("Offset", brushOffset, 0f..300f, { brushOffset = it; isSliderActive = true }, { isSliderActive = false })
                      }
                  }
              }
          }
      ) { paddingValues ->
          // Static checkerboard fills the editor Ã¢â‚¬â€ it lives OUTSIDE the stroke
          // canvas, so BlendMode.Clear reveals it instead of erasing it.
          BoxWithConstraints(
              modifier = Modifier
                  .fillMaxSize()
                  .padding(paddingValues)
                  .background(checkerboardBrush)
          ) {
              val density = LocalDensity.current
              val screenWidthPx = with(density) { maxWidth.toPx() }
              val screenHeightPx = with(density) { maxHeight.toPx() }
              val imgRatio = baseBitmap.width.toFloat() / baseBitmap.height.toFloat()
              val boundsRatio = screenWidthPx / screenHeightPx
              val drawW = if (imgRatio > boundsRatio) screenWidthPx else screenHeightPx * imgRatio
              val drawH = if (imgRatio > boundsRatio) screenWidthPx / imgRatio else screenHeightPx
              val drawLeft = (screenWidthPx - drawW) / 2f
              val drawTop = (screenHeightPx - drawH) / 2f

              LaunchedEffect(drawW) { imageDrawWidthPx = drawW }

              // Ã¢â€â‚¬Ã¢â€â‚¬ Repair brush: ALWAYS fed by the pristine source Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬
              // Keyed on pristineSourceBitmap (stable) + draw width. It is never
              // re-derived from a working bitmap, so restoring after erase +
              // AI + erase again always brings back true original pixels.
              val restoreBrush = remember(pristineSourceBitmap, drawW) {
                  val shader = BitmapShader(pristineSourceBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                  if (drawW > 1f && pristineSourceBitmap.width > 0) {
                      val sc = drawW / pristineSourceBitmap.width.toFloat()
                      shader.setLocalMatrix(Matrix().apply { setScale(sc, sc) })
                  }
                  ShaderBrush(shader)
              }

              val toolState by rememberUpdatedState(tool)
              val brushSizeState by rememberUpdatedState(brushSize)
              val brushOffsetState by rememberUpdatedState(brushOffset)
              val processingState by rememberUpdatedState(isProcessing)

              androidx.compose.foundation.Canvas(
                  modifier = Modifier
                      .fillMaxSize()
                      // Offscreen layer is required so BlendMode.Clear erases
                      // only within THIS canvas (bitmap + strokes), not the
                      // checkerboard or anything behind it.
                      .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                      .pointerInput(baseBitmap) {
                          awaitEachGesture {
                              awaitFirstDown(requireUnconsumed = false)
                              if (processingState) return@awaitEachGesture

                              var isDrawing = false
                              var isTransforming = false
                              var activeScale = scale
                              var activePan = pan

                              do {
                                  val event = awaitPointerEvent()
                                  val activePointers = event.changes.filter { it.pressed }

                                  if (activePointers.size >= 2) {
                                      // Ã¢â€â‚¬Ã¢â€â‚¬ Pan & Zoom (always available) Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬
                                      if (!isTransforming) {
                                          isTransforming = true
                                          if (isDrawing) {
                                              // Abandon the half-drawn stroke
                                              currentPath = null
                                              currentTouchPos = null
                                              isDrawing = false
                                          }
                                      }
                                      val zoomChange = event.calculateZoom()
                                      val panChange = event.calculatePan()
                                      val centroid = event.calculateCentroid(useCurrent = false)
                                      val newScale = (activeScale * zoomChange).coerceIn(1f, 12f)
                                      val actualZoom = newScale / activeScale
                                      activePan = (activePan + panChange - centroid) * actualZoom + centroid
                                      activeScale = newScale
                                      scale = activeScale
                                      pan = activePan
                                      event.changes.forEach { if (it.positionChanged()) it.consume() }
                                  } else if (activePointers.size == 1 && !isTransforming) {
                                      // Ã¢â€â‚¬Ã¢â€â‚¬ Drawing Ã¢â‚¬â€ exact coordinate math Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬
                                      val change = activePointers.first()
                                      val pointerX = change.position.x
                                      val pointerY = change.position.y
                                      val brushOffsetPx = with(density) { brushOffsetState.dp.toPx() }

                                      // 1. Calculate unscaled coordinates
                                      val unscaledX = (pointerX - activePan.x) / activeScale
                                      val unscaledY = (pointerY - activePan.y) / activeScale

                                      // 2. Subtract image centering offsets
                                      val imageX = unscaledX - drawLeft

                                      // 3. Apply brush offset (scaled)
                                      val scaledBrushOffset = brushOffsetPx / activeScale
                                      val imageY = unscaledY - drawTop - scaledBrushOffset

                                      if (!isDrawing) {
                                          isDrawing = true
                                          currentPath = Path().apply { moveTo(imageX, imageY) }
                                      } else {
                                          currentPath?.lineTo(imageX, imageY)
                                      }
                                      currentTouchPos = Offset(pointerX, pointerY - brushOffsetPx)
                                      if (change.positionChanged()) change.consume()
                                  }
                              } while (activePointers.isNotEmpty())

                              // Commit the finished stroke Ã¢â€ â€™ undo stack,
                              // and invalidate the redo stack.
                              if (isDrawing) {
                                  currentPath?.let { path ->
                                      val sizePx = with(density) { brushSizeState.dp.toPx() }
                                      undoList = undoList + MaskStroke(
                                          path = path,
                                          width = sizePx / activeScale,
                                          isErase = toolState == CutoutTool.ERASER
                                      )
                                      redoList = emptyList()
                                  }
                                  currentPath = null
                                  currentTouchPos = null
                              }
                          }
                      }
              ) {
                  withTransform({
                      translate(pan.x, pan.y)
                      scale(scale, scale, pivot = Offset.Zero)
                  }) {
                      clipRect(drawLeft, drawTop, drawLeft + drawW, drawTop + drawH) {
                          // 1) Base ML cutout
                          drawImage(
                              image = baseBitmap.asImageBitmap(),
                              dstOffset = IntOffset(drawLeft.toInt(), drawTop.toInt()),
                              dstSize = IntSize(drawW.toInt(), drawH.toInt())
                          )

                          // 2) Replay the ACTIVE strokes on top (undoList only Ã¢â‚¬â€
                          //    redoList strokes are invisible until redone).
                          translate(left = drawLeft, top = drawTop) {
                              undoList.forEach { stroke ->
                                  if (stroke.isErase) {
                                      drawPath(
                                          stroke.path, color = Color.Transparent,
                                          style = Stroke(stroke.width, cap = StrokeCap.Round, join = StrokeJoin.Round),
                                          blendMode = BlendMode.Clear
                                      )
                                  } else {
                                      drawPath(
                                          stroke.path, brush = restoreBrush,
                                          style = Stroke(stroke.width, cap = StrokeCap.Round, join = StrokeJoin.Round),
                                          blendMode = BlendMode.SrcOver
                                      )
                                  }
                              }
                              // 3) In-progress stroke, live
                              currentPath?.let { path ->
                                  val sizePx = with(density) { brushSizeState.dp.toPx() }
                                  if (toolState == CutoutTool.ERASER) {
                                      drawPath(
                                          path, color = Color.Transparent,
                                          style = Stroke(sizePx / scale, cap = StrokeCap.Round, join = StrokeJoin.Round),
                                          blendMode = BlendMode.Clear
                                      )
                                  } else {
                                      drawPath(
                                          path, brush = restoreBrush,
                                          style = Stroke(sizePx / scale, cap = StrokeCap.Round, join = StrokeJoin.Round),
                                          blendMode = BlendMode.SrcOver
                                      )
                                  }
                              }
                          }
                      }
                  }

                  // Brush cursor overlay (screen space, not transformed)
                  val cursorSizePx = with(density) { brushSizeState.dp.toPx() }
                  val cursorOffsetPx = with(density) { brushOffsetState.dp.toPx() }
                  val radius = cursorSizePx / 2f
                  val cursorColor = Color(0xFF6750A4)
                  val touch = currentTouchPos
                  if (touch != null) {
                      drawCircle(color = cursorColor, radius = radius, center = touch, style = Stroke(width = 2.dp.toPx()))
                  } else if (isSliderActive) {
                      val cx = size.width / 2f; val cy = size.height / 2f
                      drawCircle(color = cursorColor, radius = 4.dp.toPx(), center = Offset(cx, cy))
                      drawCircle(color = cursorColor.copy(alpha = 0.1f), radius = radius, center = Offset(cx, cy - cursorOffsetPx))
                      drawCircle(color = cursorColor, radius = radius, center = Offset(cx, cy - cursorOffsetPx), style = Stroke(width = 2.dp.toPx()))
                  }
              }

              if (isProcessing) {
                  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                      CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                  }
              }
          }
      }
  }

  @Composable
  private fun CutoutToolButton(
      icon: ImageVector,
      label: String,
      selected: Boolean,
      enabled: Boolean,
      onClick: () -> Unit
  ) {
      Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier
              .clip(MaterialTheme.shapes.medium)
              .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
              .clickable(enabled = enabled) { onClick() }
              .padding(horizontal = 20.dp, vertical = 8.dp)
      ) {
          Icon(
              icon, contentDescription = label,
              tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
              else MaterialTheme.colorScheme.onSurfaceVariant
          )
          Text(
              label,
              style = MaterialTheme.typography.labelSmall,
              color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
              else MaterialTheme.colorScheme.onSurfaceVariant
          )
      }
  }

  // Ã¢â€â‚¬Ã¢â€â‚¬ Shared slider row Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬
  @Composable
  fun BgSliderRow(
      label: String,
      value: Float,
      range: ClosedFloatingPointRange<Float>,
      onValueChange: (Float) -> Unit,
      onValueChangeFinished: (() -> Unit)? = null
  ) {
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(48.dp)) {
          Text(label, modifier = Modifier.width(100.dp), style = MaterialTheme.typography.labelMedium)
          Slider(
              value = value,
              onValueChange = onValueChange,
              onValueChangeFinished = onValueChangeFinished,
              valueRange = range,
              modifier = Modifier.weight(1f)
          )
          Text("%.1f".format(value), modifier = Modifier.width(40.dp), style = MaterialTheme.typography.labelSmall)
      }
  }