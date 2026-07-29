package net.z841973620.colorosliquidglass

import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.catalog.BackdropDemoScaffold
import com.kyant.backdrop.catalog.Block
import com.kyant.backdrop.catalog.components.LiquidButton
import com.kyant.backdrop.catalog.components.LiquidSlider
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Direct adaptation of AndroidLiquidGlass GlassPlaygroundContent.
 * The corner slider alone is intentionally removed; injected Views derive their own corner radius.
 */
@Composable
fun GlassPlaygroundContent() {
    val context = LocalContext.current
    val initial = remember { GlassConfig.read(context) }
    val animationScope = rememberCoroutineScope()
    val offsetAnimation = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val zoomAnimation = remember { Animatable(1f) }
    val rotationAnimation = remember { Animatable(0f) }

    var isSheetExpanded by remember { mutableStateOf(true) }
    var glassIntensity by remember { mutableFloatStateOf(initial.glassIntensity) }
    var blurRadiusDp by remember { mutableFloatStateOf(initial.blurRadius) }
    var refractionHeightFrac by remember { mutableFloatStateOf(initial.refractionHeight) }
    var refractionAmountFrac by remember { mutableFloatStateOf(initial.refractionAmount) }
    var chromaticAberration by remember { mutableFloatStateOf(initial.chromaticAberration) }
    var reflectionIntensity by remember { mutableFloatStateOf(initial.reflectionIntensity) }
    var highlightIntensity by remember { mutableFloatStateOf(initial.highlightIntensity) }
    var isRestarting by remember { mutableStateOf(false) }

    fun apply(enabled: Boolean) {
        if (isRestarting) return
        isRestarting = true
        val config = GlassConfig().also {
            it.enabled = enabled
            it.glassIntensity = glassIntensity
            it.blurRadius = blurRadiusDp
            it.refractionHeight = refractionHeightFrac
            it.refractionAmount = refractionAmountFrac
            it.chromaticAberration = chromaticAberration
            it.transparency = 0f
            it.reflectionIntensity = reflectionIntensity
            it.highlightIntensity = highlightIntensity
        }
        RootController.saveAndRestart(context, config) { success, message ->
            isRestarting = false
            Toast.makeText(context, message, if (success) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
        }
    }

    BackdropDemoScaffold { backdrop ->
        Box(
            Modifier
                .padding(top = 48.dp)
                .statusBarsPadding()
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RoundedRectangle(256.dp / 2f * 0.5f) },
                    effects = {
                        val minDimension = size.minDimension
                        vibrancy()
                        blur(blurRadiusDp.dp.toPx())
                        lens(
                            refractionHeight = refractionHeightFrac * glassIntensity * minDimension * 0.5f,
                            refractionAmount = refractionAmountFrac * glassIntensity * minDimension,
                            depthEffect = true,
                            chromaticAberration = chromaticAberration > 0f
                        )
                    },
                    highlight = { if (highlightIntensity > 0f && glassIntensity > 0f) Highlight.Plain else null },
                    onDrawSurface = {
                        drawRect(Color.White.copy(alpha = 0.08f))
                    },
                    layerBlock = {
                        val offset = offsetAnimation.value
                        val zoom = zoomAnimation.value
                        val rotation = rotationAnimation.value
                        translationX = offset.x
                        translationY = offset.y
                        scaleX = zoom
                        scaleY = zoom
                        rotationZ = rotation
                    }
                )
                .pointerInput(animationScope) {
                    fun Offset.rotateBy(angle: Float): Offset {
                        val radians = angle * (PI / 180)
                        return Offset(
                            (x * cos(radians) - y * sin(radians)).toFloat(),
                            (x * sin(radians) + y * cos(radians)).toFloat()
                        )
                    }
                    detectTransformGestures { _, pan, gestureZoom, gestureRotate ->
                        val zoom = zoomAnimation.value
                        val targetRotation = rotationAnimation.value + gestureRotate
                        val targetOffset = offsetAnimation.value + pan.rotateBy(targetRotation) * (zoom * gestureZoom)
                        animationScope.launch {
                            offsetAnimation.snapTo(targetOffset)
                            zoomAnimation.snapTo(zoom * gestureZoom)
                            rotationAnimation.snapTo(targetRotation)
                        }
                    }
                }
                .size(256.dp)
                .align(Alignment.TopCenter)
        )

        Block {
            if (isSheetExpanded) {
                val sheetBackdrop = rememberLayerBackdrop()
                val controlsScroll = rememberScrollState()
                Column(
                    Modifier
                        .padding(16.dp)
                        .heightIn(max = 420.dp)
                        .verticalScroll(controlsScroll)
                        .padding(bottom = 72.dp)
                        .navigationBarsPadding()
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { RoundedRectangle(32.dp) },
                            effects = {
                                vibrancy()
                                blur(4.dp.toPx())
                                lens(16.dp.toPx(), 32.dp.toPx())
                            },
                            highlight = { Highlight.Plain },
                            exportedBackdrop = sheetBackdrop,
                            onDrawSurface = { drawRect(Color.White.copy(alpha = 0.5f)) }
                        )
                        .padding(24.dp)
                        .align(Alignment.BottomCenter),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    PlaygroundSlider("液态玻璃强度", { glassIntensity }, 0f..1f, 0.001f, sheetBackdrop) { glassIntensity = it }
                    PlaygroundSlider("模糊半径", { blurRadiusDp }, 0f..32f, 0.01f, sheetBackdrop) { blurRadiusDp = it }
                    PlaygroundSlider("折射高度", { refractionHeightFrac }, 0f..1f, 0.001f, sheetBackdrop) { refractionHeightFrac = it }
                    PlaygroundSlider("扭曲强度", { refractionAmountFrac }, 0f..1f, 0.001f, sheetBackdrop) { refractionAmountFrac = it }
                    PlaygroundSlider("反射", { reflectionIntensity }, 0f..1f, 0.001f, sheetBackdrop) { reflectionIntensity = it }
                    PlaygroundSlider("高光", { highlightIntensity }, 0f..1f, 0.001f, sheetBackdrop) { highlightIntensity = it }
                    PlaygroundSlider("色差", { chromaticAberration }, 0f..1f, 0.001f, sheetBackdrop) { chromaticAberration = it }
                }
            }
        }

        Block {
            LiquidButton(
                { isSheetExpanded = !isSheetExpanded }, backdrop,
                Modifier.padding(20.dp).navigationBarsPadding().align(Alignment.BottomStart),
                tint = Color(0xFFFF8D28)
            ) { BasicText(if (isSheetExpanded) "🔽" else "🔼", style = TextStyle(Color.White, 15.sp)) }

            LiquidButton(
                {
                    animationScope.launch {
                        launch { offsetAnimation.animateTo(Offset.Zero) }
                        launch { zoomAnimation.animateTo(1f) }
                        launch { rotationAnimation.animateTo(0f) }
                    }
                    glassIntensity = 1f
                    blurRadiusDp = 0f
                    refractionHeightFrac = 0.2f
                    refractionAmountFrac = 0.2f
                    chromaticAberration = 0f
                    reflectionIntensity = 1f
                    highlightIntensity = 1f
                }, backdrop,
                Modifier.padding(20.dp).navigationBarsPadding().align(Alignment.BottomEnd),
                tint = Color(0xFFFF8D28)
            ) { BasicText("重置", style = TextStyle(Color.White, 15.sp)) }
        }

        Row(
            Modifier.padding(horizontal = 16.dp).statusBarsPadding().align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            LiquidButton({ apply(true) }, backdrop, tint = Color(0xFF0088FF)) {
                BasicText(if (isRestarting) "正在重启…" else "启用并重启 Launcher", style = TextStyle(Color.White, 14.sp))
            }
            LiquidButton({ apply(false) }, backdrop, tint = Color(0xFFE34D59)) {
                BasicText("关闭修改并重启", style = TextStyle(Color.White, 14.sp))
            }
        }
    }
}

@Composable
private fun PlaygroundSlider(
    label: String,
    value: () -> Float,
    range: ClosedFloatingPointRange<Float>,
    threshold: Float,
    backdrop: com.kyant.backdrop.Backdrop,
    onChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText(label)
        LiquidSlider(value, onChange, range, threshold, backdrop)
    }
}
