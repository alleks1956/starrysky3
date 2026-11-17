package com.example.timelapsecamera

import android.Manifest
import android.annotation.SuppressLint
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private var targetHour: Int? = null
    private var targetMinute: Int? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private var availableSizes: List<Size> = emptyList()
    private var selectedSize: Size? = null

    private lateinit var viewFinder: PreviewView
    private lateinit var tvResolutionBadge: TextView
    private lateinit var btnBatteryOpt: Button
    private lateinit var tvStatus: TextView
    private lateinit var spinnerResolution: Spinner
    private lateinit var btnPickTime: Button
    private lateinit var etInterval: EditText
    private lateinit var etCount: EditText
    private lateinit var btnStart: Button

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions[Manifest.permission.CAMERA] == true) {
                if (!CameraForegroundService.isRunningFlow.value) {
                    startPreviewCamera()
                }
            } else {
                Toast.makeText(this, "Без доступа к камере работа невозможна", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)
        tvResolutionBadge = findViewById(R.id.tvResolutionBadge)
        btnBatteryOpt = findViewById(R.id.btnBatteryOpt)
        tvStatus = findViewById(R.id.tvStatus)
        spinnerResolution = findViewById(R.id.spinnerResolution)
        btnPickTime = findViewById(R.id.btnPickTime)
        etInterval = findViewById(R.id.etInterval)
        etCount = findViewById(R.id.etCount)
        btnStart = findViewById(R.id.btnStart)

        loadSavedSize()
        checkAndRequestPermissions()
        observeServiceState()

        btnBatteryOpt.setOnClickListener { requestIgnoreBatteryOptimization() }
        btnPickTime.setOnClickListener { showTimePicker() }

        btnStart.setOnClickListener {
            if (CameraForegroundService.isRunningFlow.value) {
                stopShootingService()
            } else {
                startShootingService()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateBatteryButtonVisibility()
    }

    // ---------- Разрешение ----------

    private fun loadSavedSize() {
        val prefs = getSharedPreferences(CameraResolution.PREFS_NAME, Context.MODE_PRIVATE)
        val w = prefs.getInt(CameraResolution.KEY_WIDTH, 0)
        val h = prefs.getInt(CameraResolution.KEY_HEIGHT, 0)
        selectedSize = if (w > 0 && h > 0) Size(w, h) else null
    }

    private fun saveSelectedSize(size: Size) {
        getSharedPreferences(CameraResolution.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(CameraResolution.KEY_WIDTH, size.width)
            .putInt(CameraResolution.KEY_HEIGHT, size.height)
            .apply()
    }

    /** Заполняет выпадающий список один раз; по умолчанию выбирается максимум. */
    private fun setupResolutionSpinner(sizes: List<Size>) {
        if (availableSizes.isNotEmpty() || sizes.isEmpty()) return
        availableSizes = sizes

        if (selectedSize == null || selectedSize !in sizes) {
            selectedSize = sizes.last()
        }

        val labels = sizes.map { CameraResolution.label(it) }
        val adapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_item,
            labels
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.setTextColor(Color.parseColor("#00E676"))
                v.textSize = 14f
                return v
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent) as TextView
                v.setTextColor(Color.WHITE)
                return v
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        spinnerResolution.adapter = adapter
        spinnerResolution.setSelection(sizes.indexOf(selectedSize))

        spinnerResolution.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val size = availableSizes.getOrNull(position) ?: return
                if (size == selectedSize) return
                selectedSize = size
                saveSelectedSize(size)
                if (!CameraForegroundService.isRunningFlow.value) {
                    startPreviewCamera()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // ---------- Батарея ----------

    private fun updateBatteryButtonVisibility() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isIgnoring = powerManager.isIgnoringBatteryOptimizations(packageName)
        btnBatteryOpt.visibility = if (isIgnoring) View.GONE else View.VISIBLE
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimization() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(
                    this,
                    "Отключите ограничение батареи для приложения в настройках телефона",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ---------- Разрешения ----------

    private fun checkAndRequestPermissions() {
        val needed = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            if (!CameraForegroundService.isRunningFlow.value) {
                startPreviewCamera()
            }
        } else {
            requestPermissionsLauncher.launch(missing.toTypedArray())
        }
    }

    // ---------- Состояние сервиса ----------

    private fun observeServiceState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    CameraForegroundService.statusFlow.collect { status ->
                        tvStatus.text = status
                    }
                }
                launch {
                    CameraForegroundService.isRunningFlow.collect { isRunning ->
                        btnStart.text = if (isRunning) "Остановить съемку" else "Запустить по таймеру"
                        btnPickTime.isEnabled = !isRunning
                        etInterval.isEnabled = !isRunning
                        etCount.isEnabled = !isRunning
                        spinnerResolution.isEnabled = !isRunning

                        if (isRunning) {
                            cameraProvider?.unbindAll()
                        } else if (ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            startPreviewCamera()
                        }
                    }
                }
            }
        }
    }

    // ---------- Превью ----------

    private fun startPreviewCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            cameraProvider = provider

            // Список доступных размеров берём до привязки use case'ов
            try {
                val info = provider.getCameraInfo(CameraSelector.DEFAULT_BACK_CAMERA)
                setupResolutionSpinner(CameraResolution.getSupportedJpegSizes(info))
            } catch (_: Exception) {
                // если не удалось получить список — работаем в режиме "максимум"
            }

            val preview = Preview.Builder()
                .setResolutionSelector(CameraResolution.buildPreviewSelector(selectedSize))
                .build()
                .also { it.setSurfaceProvider(viewFinder.surfaceProvider) }

            val probeImageCapture = ImageCapture.Builder()
                .setResolutionSelector(CameraResolution.buildCaptureSelector(selectedSize))
                .setOutputFormat(ImageCapture.OUTPUT_FORMAT_JPEG)
                .build()

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    probeImageCapture
                )

                val res = probeImageCapture.resolutionInfo?.resolution
                tvResolutionBadge.text = if (res != null) {
                    val megapixels = (res.width.toLong() * res.height) / 1_000_000.0
                    String.format(
                        Locale.getDefault(),
                        "Фото: %d×%d (%.1f МП)",
                        res.width,
                        res.height,
                        megapixels
                    )
                } else {
                    "Фото: разрешение выбрано"
                }
            } catch (_: Exception) {
                tvResolutionBadge.text = "Ошибка камеры"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ---------- Время и запуск ----------

    private fun showTimePicker() {
        val now = Calendar.getInstance()
        val initialHour = targetHour ?: now.get(Calendar.HOUR_OF_DAY)
        val initialMinute = targetMinute ?: (now.get(Calendar.MINUTE) + 1) % 60

        TimePickerDialog(
            this,
            { _, selectedHour, selectedMinute ->
                targetHour = selectedHour
                targetMinute = selectedMinute
                btnPickTime.text = String.format(
                    Locale.getDefault(),
                    "Старт: %02d:%02d",
                    selectedHour,
                    selectedMinute
                )
            },
            initialHour,
            initialMinute,
            true
        ).show()
    }

    private fun startShootingService() {
        val hour = targetHour
        val minute = targetMinute

        if (hour == null || minute == null) {
            Toast.makeText(this, "Сначала выберите время старта", Toast.LENGTH_SHORT).show()
            return
        }

        val intervalSec = etInterval.text.toString().toLongOrNull() ?: 1L
        val totalCount = etCount.text.toString().toIntOrNull() ?: 1

        if (totalCount <= 0) {
            Toast.makeText(this, "Количество снимков должно быть больше 0", Toast.LENGTH_SHORT).show()
            return
        }

        val now = Calendar.getInstance()
        val targetCalendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now.timeInMillis) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        cameraProvider?.unbindAll()

        val size = selectedSize
        val intent = Intent(this, CameraForegroundService::class.java).apply {
            action = CameraForegroundService.ACTION_START
            putExtra(CameraForegroundService.EXTRA_TARGET_TIME_MS, targetCalendar.timeInMillis)
            putExtra(CameraForegroundService.EXTRA_INTERVAL_SEC, intervalSec)
            putExtra(CameraForegroundService.EXTRA_TOTAL_COUNT, totalCount)
            putExtra(CameraForegroundService.EXTRA_WIDTH, size?.width ?: 0)
            putExtra(CameraForegroundService.EXTRA_HEIGHT, size?.height ?: 0)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopShootingService() {
        val intent = Intent(this, CameraForegroundService::class.java).apply {
            action = CameraForegroundService.ACTION_STOP
        }
        startService(intent)
    }
}
