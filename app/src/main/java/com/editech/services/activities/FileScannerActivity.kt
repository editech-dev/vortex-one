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
    private val allApkFiles = mutableListOf<ApkFile>() // Store all found APKs
    private lateinit var adapter: ApkFileAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFileScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupButtons()
        setupSearch()
        checkPermissionsAndScan()
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterApks(s.toString())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun filterApks(query: String) {
        val filteredList = if (query.isEmpty()) {
            allApkFiles
        } else {
            allApkFiles.filter { it.name.contains(query, ignoreCase = true) }
        }

        apkFiles.clear()
        apkFiles.addAll(filteredList)
        adapter.notifyDataSetChanged()

        if (apkFiles.isEmpty()) {
             if (query.isNotEmpty()) {
                 binding.tvStatus.text = "No searching result"
             } else if (allApkFiles.isEmpty()){
                 binding.tvStatus.text = "No APK files found"
             }
            binding.layoutEmptyState.visibility = View.VISIBLE
            binding.rvApkFiles.visibility = View.GONE
        } else {
            binding.tvStatus.text = "Encontrados ${apkFiles.size} APKs"
            binding.layoutEmptyState.visibility = View.GONE
            binding.rvApkFiles.visibility = View.VISIBLE
        }
    }

    private fun checkPermissionsAndScan() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (android.os.Environment.isExternalStorageManager()) {
                scanForApks()
            } else {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.addCategory("android.intent.category.DEFAULT")
                    intent.data = android.net.Uri.parse("package:$packageName")
                    startActivityForResult(intent, REQUEST_CODE_PERMISSION_STORAGE)
                } catch (e: Exception) {
                    val intent = Intent()
                    intent.action = android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                    startActivityForResult(intent, REQUEST_CODE_PERMISSION_STORAGE)
                }
            }
        } else {
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                scanForApks()
            } else {
                requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_CODE_PERMISSION_STORAGE)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSION_STORAGE) {
            if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                scanForApks()
            } else {
                binding.tvStatus.text = "Permiso de almacenamiento denegado"
                binding.layoutEmptyState.visibility = View.VISIBLE
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_PERMISSION_STORAGE) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                if (android.os.Environment.isExternalStorageManager()) {
                    scanForApks()
                } else {
                    binding.tvStatus.text = "Permiso de acceso a todos los archivos denegado"
                    binding.layoutEmptyState.visibility = View.VISIBLE
                }
            }
        }
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
        binding.etSearch.isEnabled = false // Disable search while scanning

        CoroutineScope(Dispatchers.IO).launch {
            apkFiles.clear()
            allApkFiles.clear()

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
                allApkFiles.addAll(uniqueApks)
                binding.etSearch.isEnabled = true
                filterApks(binding.etSearch.text.toString()) // Apply current filter
                binding.progressBar.visibility = View.GONE
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
        const val REQUEST_CODE_PERMISSION_STORAGE = 1002
    }
}
