package com.example.timelapsecamera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraInfo
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import java.util.Locale

object CameraResolution {

    const val MIN_SHORT_SIDE = 720

    const val PREFS_NAME = "camera_prefs"
    const val KEY_WIDTH = "res_width"
    const val KEY_HEIGHT = "res_height"

    /**
     * Все JPEG-разрешения основной камеры с короткой стороной >= 720,
     * только форматы 4:3 и 16:9, по возрастанию числа пикселей (последний = максимум).
     */
    @Suppress("UnsafeOptInUsageError")
    fun getSupportedJpegSizes(cameraInfo: CameraInfo): List<Size> {
        val map = Camera2CameraInfo.from(cameraInfo)
            .getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return emptyList()

        val all = mutableListOf<Size>()
        map.getOutputSizes(ImageFormat.JPEG)?.let { all += it }
        map.getHighResolutionOutputSizes(ImageFormat.JPEG)?.let { all += it }

        return all
            .filter { minOf(it.width, it.height) >= MIN_SHORT_SIDE }
            .filter { is43(it) || is169(it) }
            .distinct()
            .sortedBy { it.width.toLong() * it.height }
    }

    private fun is43(s: Size) = s.width.toLong() * 3 == s.height.toLong() * 4 ||
            s.height.toLong() * 3 == s.width.toLong() * 4

    private fun is169(s: Size) = s.width.toLong() * 9 == s.height.toLong() * 16 ||
            s.height.toLong() * 9 == s.width.toLong() * 16

    private fun aspectStrategyFor(size: Size?): AspectRatioStrategy {
        val ratio = if (size != null && is169(size)) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3
        return AspectRatioStrategy(ratio, AspectRatioStrategy.FALLBACK_RULE_AUTO)
    }

    /** Селектор для ImageCapture. size == null -> максимальное доступное. */
    fun buildCaptureSelector(size: Size?): ResolutionSelector {
        val builder = ResolutionSelector.Builder()
            // нужно, чтобы стали доступны самые большие размеры
            .setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)

        return if (size == null) {
            builder.setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY).build()
        } else {
            builder
                .setAspectRatioStrategy(aspectStrategyFor(size))
                .setResolutionStrategy(
                    ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                )
                .build()
        }
    }

    /** Селектор для превью: только то же соотношение сторон, что у снимка. */
    fun buildPreviewSelector(size: Size?): ResolutionSelector =
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(aspectStrategyFor(size))
            .build()

    fun label(size: Size): String {
        val mp = size.width.toLong() * size.height / 1_000_000.0
        return String.format(Locale.getDefault(), "%d×%d (%.1f МП)", size.width, size.height, mp)
    }
}
