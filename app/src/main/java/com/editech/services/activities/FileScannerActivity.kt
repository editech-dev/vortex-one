package com.editech.services.activities

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.editech.services.adapters.ApkFileAdapter
import com.editech.services.databinding.ActivityFileScannerBinding
import com.editech.services.models.ApkFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * FileScannerActivity: Escanea el almacenamiento local en busca de archivos APK
 * No usa SAF (Storage Access Framework) para evitar problemas con Fire OS
 */
class FileScannerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFileScannerBinding
    private val apkFiles = mutableListOf<ApkFile>()
    private lateinit var adapter: ApkFileAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFileScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupButtons()
        scanForApks()
    }

    private fun setupRecyclerView() {
        adapter = ApkFileAdapter(apkFiles) { apkFile ->
            // Al seleccionar un APK, devolverlo a MainActivity
            val resultIntent = Intent().apply {
                putExtra(EXTRA_APK_PATH, apkFile.path)
                putExtra(EXTRA_APK_NAME, apkFile.name)
            }
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }

        binding.rvApkFiles.apply {
            layoutManager = LinearLayoutManager(this@FileScannerActivity)
            adapter = this@FileScannerActivity.adapter
        }
    }

    private fun setupButtons() {
        binding.btnCancel.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        binding.btnRescan.setOnClickListener {
            scanForApks()
        }
    }

    private fun scanForApks() {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvStatus.text = "Escaneando almacenamiento..."

        CoroutineScope(Dispatchers.IO).launch {
            apkFiles.clear()

            // Lista de directorios a escanear
            val dirsToScan = listOf(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                Environment.getExternalStorageDirectory(),
                File("/storage/emulated/0/Download"),
                File("/storage/emulated/0/Downloads"),
                File("/sdcard/Download"),
                File("/sdcard/Downloads")
            )

            val foundApks = mutableListOf<ApkFile>()

            dirsToScan.forEach { dir ->
                if (dir.exists() && dir.isDirectory) {
                    scanDirectoryRecursive(dir, foundApks)
                }
            }

            // Eliminar duplicados por path
            val uniqueApks = foundApks.distinctBy { it.path }

            withContext(Dispatchers.Main) {
                apkFiles.addAll(uniqueApks)
                adapter.notifyDataSetChanged()
                binding.progressBar.visibility = View.GONE

                if (apkFiles.isEmpty()) {
                    binding.tvStatus.text = "No se encontraron archivos APK"
                    binding.layoutEmptyState.visibility = View.VISIBLE
                } else {
                    binding.tvStatus.text = "Encontrados ${apkFiles.size} APKs"
                    binding.layoutEmptyState.visibility = View.GONE
                }
            }
        }
    }

    /**
     * Escanea recursivamente un directorio en busca de archivos .apk
     */
    private fun scanDirectoryRecursive(dir: File, results: MutableList<ApkFile>, maxDepth: Int = 3, currentDepth: Int = 0) {
        if (currentDepth > maxDepth) return

        try {
            dir.listFiles()?.forEach { file ->
                when {
                    file.isFile && file.extension.equals("apk", ignoreCase = true) -> {
                        results.add(
                            ApkFile(
                                name = file.nameWithoutExtension,
                                path = file.absolutePath,
                                size = file.length()
                            )
                        )
                    }
                    file.isDirectory && !file.name.startsWith(".") -> {
                        scanDirectoryRecursive(file, results, maxDepth, currentDepth + 1)
                    }
                }
            }
        } catch (e: SecurityException) {
            // Ignorar directorios sin permiso
        }
    }

    companion object {
        const val EXTRA_APK_PATH = "apk_path"
        const val EXTRA_APK_NAME = "apk_name"
        const val REQUEST_CODE_SELECT_APK = 1001
    }
}
