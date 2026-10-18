package com.example.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Opens a transferred file with whatever app on the phone can show it (gallery, PDF viewer...). */
fun openFile(context: Context, path: String, mimeType: String) {
    val file = File(path)
    if (!file.exists()) {
        Toast.makeText(context, "This file is no longer available", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val type = mimeType.takeIf { it.isNotBlank() && it != "application/octet-stream" } ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, type)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app on this phone can open this file. Use Save instead.", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Could not open the file: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

/**
 * Returns a function (path, fileName) that lets the user pick where to save a copy of a file.
 * Uses the system file picker, so no storage permission is needed.
 */
@Composable
fun rememberFileSaver(): (String, String) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Pair<String, String>?>(null) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        val job = pending
        pending = null
        if (uri != null && job != null) {
            scope.launch(Dispatchers.IO) {
                val message = try {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        File(job.first).inputStream().use { input -> input.copyTo(out) }
                    }
                    "Saved " + job.second
                } catch (e: Exception) {
                    "Could not save the file: ${e.message}"
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    return { path, name ->
        pending = path to name
        launcher.launch(name)
    }
}
