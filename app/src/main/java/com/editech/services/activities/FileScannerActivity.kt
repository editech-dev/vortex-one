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
import com.editech.services.R

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
        
        loadBannerAd()
    }
    
    private fun loadBannerAd() {
        CoroutineScope(Dispatchers.IO).launch {
            // Wait for SDK to initialize (poll for up to 20 seconds)
            var attempts = 0
            while (!com.editech.services.utils.AdManager.isSdkInitialized() && attempts < 20) {
                kotlinx.coroutines.delay(1000)
                attempts++
            }
            // Wait an extra second for safety
            kotlinx.coroutines.delay(1000)

            withContext(Dispatchers.Main) {
               val bannerContainer = findViewById<android.widget.RelativeLayout>(com.editech.services.R.id.bannerContainer)
               if (bannerContainer != null) {
                   com.editech.services.utils.AdManager.loadBanner(this@FileScannerActivity, bannerContainer)
               }
            }
        }
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
                 binding.tvStatus.text = getString(R.string.status_no_results)
             } else if (allApkFiles.isEmpty()){
                 binding.tvStatus.text = getString(R.string.status_no_apks_found)
             }
            binding.layoutEmptyState.visibility = View.VISIBLE
            binding.rvApkFiles.visibility = View.GONE
        } else {
            binding.tvStatus.text = getString(R.string.status_apks_found, apkFiles.size)
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
                binding.tvStatus.text = getString(R.string.status_storage_permission_denied)
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
                    binding.tvStatus.text = getString(R.string.status_all_files_permission_denied)
                    binding.layoutEmptyState.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = ApkFileAdapter(
            apkFiles = apkFiles,
            onInstallClick = { apkFile ->
                val resultIntent = Intent().apply {
                    putExtra(EXTRA_APK_PATH, apkFile.path)
                    putExtra(EXTRA_APK_NAME, apkFile.name)
                }
                setResult(Activity.RESULT_OK, resultIntent)
                finish()
            },
            onDeleteClick = { apkFile ->
                confirmAndDeleteApk(apkFile)
            }
        )

        binding.rvApkFiles.apply {
            layoutManager = LinearLayoutManager(this@FileScannerActivity)
            adapter = this@FileScannerActivity.adapter
        }
    }

    private fun confirmAndDeleteApk(apkFile: ApkFile) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_delete_apk_title))
            .setMessage(getString(R.string.dialog_delete_apk_message, apkFile.name))
            .setPositiveButton(getString(R.string.action_delete)) { _, _ ->
                val file = File(apkFile.path)
                if (file.exists() && file.delete()) {
                    val index = apkFiles.indexOf(apkFile)
                    apkFiles.remove(apkFile)
                    allApkFiles.remove(apkFile)
                    if (index != -1) {
                        adapter.notifyItemRemoved(index)
                    } else {
                        adapter.notifyDataSetChanged()
                    }
                    if (apkFiles.isEmpty()) {
                        binding.layoutEmptyState.visibility = View.VISIBLE
                        binding.rvApkFiles.visibility = View.GONE
                        binding.tvStatus.text = getString(R.string.status_no_apks_found)
                    } else {
                        binding.tvStatus.text = getString(R.string.status_apks_found, apkFiles.size)
                    }
                    android.widget.Toast.makeText(
                        this,
                        getString(R.string.toast_apk_deleted, apkFile.name),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } else {
                    android.widget.Toast.makeText(
                        this,
                        getString(R.string.toast_apk_delete_error, apkFile.name),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    private fun setupButtons() {
        binding.btnBackHeader.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

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
        binding.tvStatus.text = getString(R.string.status_scanning_storage)
        binding.etSearch.isEnabled = false // Disable search while scanning

        CoroutineScope(Dispatchers.IO).launch {
            apkFiles.clear()
            allApkFiles.clear()

            val dirsToScan = mutableListOf<File>()
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (downloadDir != null && downloadDir.exists()) {
                dirsToScan.add(downloadDir.canonicalFile)
            }
            val rootStorage = Environment.getExternalStorageDirectory()
            if (rootStorage != null && rootStorage.exists()) {
                dirsToScan.add(rootStorage.canonicalFile)
            }

            val uniqueDirs = dirsToScan.distinctBy { it.canonicalPath }
            val foundApks = mutableListOf<ApkFile>()
            val visitedPaths = HashSet<String>()

            uniqueDirs.forEach { dir ->
                if (dir.exists() && dir.isDirectory) {
                    scanDirectoryRecursive(dir, foundApks, visitedPaths)
                }
            }

            // Eliminar duplicados absolutos por canonicalPath
            val uniqueApks = foundApks.distinctBy { 
                try {
                    File(it.path).canonicalPath
                } catch (e: Exception) {
                    it.path
                }
            }

            withContext(Dispatchers.Main) {
                allApkFiles.addAll(uniqueApks)
                binding.etSearch.isEnabled = true
                filterApks(binding.etSearch.text.toString()) // Apply current filter
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    /**
     * Escanea recursivamente un directorio en busca de archivos .apk evitando bucles y duplicados
     */
    private fun scanDirectoryRecursive(
        dir: File,
        results: MutableList<ApkFile>,
        visitedPaths: HashSet<String>,
        maxDepth: Int = 3,
        currentDepth: Int = 0
    ) {
        if (currentDepth > maxDepth) return

        try {
            val canonicalDir = dir.canonicalFile
            val canonicalDirPath = canonicalDir.canonicalPath
            if (visitedPaths.contains(canonicalDirPath)) {
                return
            }
            visitedPaths.add(canonicalDirPath)

            canonicalDir.listFiles()?.forEach { file ->
                try {
                    val canonicalFile = file.canonicalFile
                    when {
                        canonicalFile.isFile && canonicalFile.extension.equals("apk", ignoreCase = true) -> {
                            val canonicalPath = canonicalFile.canonicalPath
                            if (!visitedPaths.contains(canonicalPath)) {
                                visitedPaths.add(canonicalPath)
                                results.add(
                                    ApkFile(
                                        name = canonicalFile.nameWithoutExtension,
                                        path = canonicalFile.absolutePath,
                                        size = canonicalFile.length()
                                    )
                                )
                            }
                        }
                        canonicalFile.isDirectory && !canonicalFile.name.startsWith(".") -> {
                            scanDirectoryRecursive(canonicalFile, results, visitedPaths, maxDepth, currentDepth + 1)
                        }
                    }
                } catch (ignored: Exception) {}
            }
        } catch (e: SecurityException) {
            // Ignorar directorios sin permiso
        } catch (ignored: Exception) {}
    }

    companion object {
        const val EXTRA_APK_PATH = "apk_path"
        const val EXTRA_APK_NAME = "apk_name"
        const val REQUEST_CODE_SELECT_APK = 1001
        const val REQUEST_CODE_PERMISSION_STORAGE = 1002
    }
}
