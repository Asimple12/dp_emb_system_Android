package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.example.myapplication.workers.TTSWorker
import java.io.File
import com.example.myapplication.models.BatchManifest
import com.example.myapplication.models.BatchItem
import com.example.myapplication.utils.ManifestIO

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_PERMISSIONS = 1001}

    //UI
    private lateinit var etWavPath: EditText
    private lateinit var etCsvPath: EditText
    private lateinit var etServerUrl: EditText
    private lateinit var btnRun: Button
    private lateinit var btnRetryTts: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvDetails: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setDefaults()
        requestPermissions()

        //启动完整流水线
        btnRun.setOnClickListener {
            val wavPath   = etWavPath.text.toString().trim()
            val csvPath   = etCsvPath.text.toString().trim()
            val serverUrl = etServerUrl.text.toString().trim()

            if (wavPath.isEmpty() || csvPath.isEmpty() || serverUrl.isEmpty()) {
                toast("请填写所有输入项")
                return@setOnClickListener
            }

            val inputFile = File(wavPath)
            if (!inputFile.exists()) {
                toast("路径不存在：$wavPath")
                return@setOnClickListener
            }
            if (!File(csvPath).exists()) {
                toast("CSV文件不存在：$csvPath")
                return@setOnClickListener
            }

            val wavFiles = collectWavFiles(inputFile)
            if (wavFiles.isEmpty()) {
                toast("未找到wav文件")
                return@setOnClickListener
            }

            //初始化 ManifestIO
            ManifestIO.init(this)

            //创建 batchDir
            val batchDir = File(getExternalFilesDir(null), "batch_${System.currentTimeMillis()}")
            batchDir.mkdirs()

            //复制 wav 到 batchDir/wav/
            val wavDir = File(batchDir, "wav").also { it.mkdirs() }
            wavFiles.forEach { f ->
                f.copyTo(File(wavDir, f.name), overwrite = true)
            }

            //构建 manifest 并写入
            val manifestFile = File(batchDir, "manifest.json")
            try {
                val items: MutableList<BatchItem> = wavFiles.map { f ->
                    BatchItem(
                        id= f.nameWithoutExtension,
                        wavPath       = File(wavDir, f.name).absolutePath,
                        embeddingPath = null
                    )
                }.toMutableList()

                val manifest = BatchManifest(
                    batchId= batchDir.name,
                    createdAt = System.currentTimeMillis(),
                    items     = items
                )

                ManifestIO.writeManifest(manifest, manifestFile)
                Log.i(TAG, "manifest写入成功，共${items.size}个item")

            } catch (e: Exception) {
                Log.e(TAG, "manifest写入失败: ${e.message}", e)
                toast("manifest创建失败，无法启动流水线")
                return@setOnClickListener
            }

            //启动流水线
            tvStatus.text = "流水线启动中...\nbatchDir: ${batchDir.name}"
            Log.i(TAG, "启动流水线，batchDir=${batchDir.absolutePath}")

            StageRunner.enqueueFullPipelineStructured(
                context= this,
                batchDir = batchDir
            )
            observeWork("tts_batch")
        }

        btnRetryTts.setOnClickListener {
            val serverUrl = etServerUrl.text.toString().trim()
            if (serverUrl.isEmpty()) {
                toast("请填写服务器地址")
                return@setOnClickListener
            }
            val batchDir = getLatestBatchDir() ?: run {
                toast("找不到上次的batchDir")
                return@setOnClickListener
            }
            tvStatus.text = "重试TTS阶段..."
            val ttsRetryRequest = OneTimeWorkRequestBuilder<TTSWorker>()
                .setInputData(
                    workDataOf("batchDir" to batchDir.absolutePath)
                )
                .addTag("tts_batch_retry")
                .build()
            WorkManager.getInstance(this).enqueue(ttsRetryRequest)
            observeWork("tts_batch_retry")
        }
    }

    //收集wav文件
    private fun collectWavFiles(inputFile: File): List<File> {
        return when {
            inputFile.isFile && inputFile.extension.lowercase() == "wav" ->
                listOf(inputFile)
            inputFile.isDirectory ->
                collectWavFilesRecursive(inputFile)
            else ->
                emptyList()
        }
    }

    private fun collectWavFilesRecursive(dir: File): List<File> {
        val result = mutableListOf<File>()
        dir.listFiles()?.forEach { file ->
            when {
                file.isFile && file.extension.lowercase() == "wav" ->
                    result.add(file)
                file.isDirectory ->
                    result.addAll(collectWavFilesRecursive(file))
            }
        }
        return result.sortedBy { it.absolutePath }
    }

    private fun observeWork(tag: String) {
        WorkManager.getInstance(this)
            .getWorkInfosByTagLiveData(tag)
            .observe(this) { infos ->
                val info = infos?.firstOrNull() ?: return@observe
                when (info.state) {
                    WorkInfo.State.RUNNING    -> tvStatus.text = "[$tag] 运行中..."
                    WorkInfo.State.SUCCEEDED  -> tvStatus.text = "[$tag] ✅ 完成！"
                    WorkInfo.State.FAILED     -> tvStatus.text = "[$tag] ❌ 失败"
                    WorkInfo.State.CANCELLED  -> tvStatus.text = "[$tag] 已取消"
                    else -> {}
                }
            }
    }

    private fun getLatestBatchDir(): File? {
        val parent = getExternalFilesDir(null) ?: return null
        return parent.listFiles { f ->
            f.isDirectory && f.name.startsWith("batch_")
        }?.maxByOrNull { it.name }
    }

    //权限请求
    private fun requestPermissions() {
        val perms = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        val denied = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (denied.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, denied.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    //绑定View
    private fun bindViews() {
        etWavPath   = findViewById(R.id.etWavPath)
        etCsvPath   = findViewById(R.id.etCsvPath)
        etServerUrl = findViewById(R.id.etServerUrl)
        btnRun      = findViewById(R.id.btnRun)
        btnRetryTts = findViewById(R.id.btnRetryTts)
        tvStatus    = findViewById(R.id.tvStatus)
        tvDetails   = findViewById(R.id.tvDetails)
    }

    private fun setDefaults() {
        etServerUrl.setText("http://10.0.2.2:5000/synthesize")
        etCsvPath.setText("./metadata.csv")
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}