package com.remmi.browser.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PdfPrintHelper {
  private const val TAG = "PdfPrintHelper"

  /**
   * Export webpage as a PDF file, save to public Downloads,
   * and offer to open/share the PDF file.
   */
  fun exportPageAsPdf(
    context: Context,
    pdfStreamProvider: () -> org.mozilla.geckoview.GeckoResult<InputStream>,
    pageTitle: String,
    onFinished: ((File?) -> Unit)? = null
  ) {
    val mainHandler = Handler(Looper.getMainLooper())
    Toast.makeText(context, "Exporting page as PDF...", Toast.LENGTH_SHORT).show()

    val sanitizedTitle = sanitizeFilename(if (pageTitle.isBlank()) "WebPage" else pageTitle)
    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val fileName = "${sanitizedTitle}_$timeStamp.pdf"

    try {
      pdfStreamProvider().accept(
        { inputStream: InputStream? ->
          if (inputStream == null) {
            mainHandler.post {
              Toast.makeText(context, "Failed to generate PDF: stream empty", Toast.LENGTH_SHORT).show()
              onFinished?.invoke(null)
            }
            return@accept
          }

          // Save to App Cache / Internal storage for sharing
          val cacheFile = File(context.cacheDir, fileName)
          try {
            inputStream.use { input ->
              FileOutputStream(cacheFile).use { output ->
                input.copyTo(output)
              }
            }

            // Save a copy to Public Downloads
            saveToDownloads(context, cacheFile, fileName)

            mainHandler.post {
              Toast.makeText(context, "PDF saved: $fileName", Toast.LENGTH_LONG).show()
              openOrSharePdf(context, cacheFile)
              onFinished?.invoke(cacheFile)
            }
          } catch (e: Exception) {
            Log.e(TAG, "Error writing PDF file", e)
            mainHandler.post {
              Toast.makeText(context, "Error saving PDF: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
              onFinished?.invoke(null)
            }
          }
        },
        { throwable: Throwable? ->
          Log.e(TAG, "Failed to export PDF from GeckoSession", throwable)
          mainHandler.post {
            Toast.makeText(
              context,
              "Export failed: ${throwable?.localizedMessage ?: "Unknown error"}",
              Toast.LENGTH_SHORT
            ).show()
            onFinished?.invoke(null)
          }
        }
      )
    } catch (e: Exception) {
      Log.e(TAG, "Exception calling saveAsPdf", e)
      Toast.makeText(context, "Export error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
      onFinished?.invoke(null)
    }
  }

  /**
   * Print the current webpage using Android system PrintManager.
   */
  fun printPage(
    context: Context,
    pdfStreamProvider: () -> org.mozilla.geckoview.GeckoResult<InputStream>,
    pageTitle: String,
    onFinished: (() -> Unit)? = null
  ) {
    val mainHandler = Handler(Looper.getMainLooper())
    Toast.makeText(context, "Preparing print spooler...", Toast.LENGTH_SHORT).show()

    val sanitizedTitle = sanitizeFilename(if (pageTitle.isBlank()) "Remmi Page" else pageTitle)

    try {
      pdfStreamProvider().accept(
        { inputStream: InputStream? ->
          if (inputStream == null) {
            mainHandler.post {
              Toast.makeText(context, "Unable to print: PDF data is empty", Toast.LENGTH_SHORT).show()
              onFinished?.invoke()
            }
            return@accept
          }

          val tempPrintFile = File(context.cacheDir, "print_job_${System.currentTimeMillis()}.pdf")
          try {
            inputStream.use { input ->
              FileOutputStream(tempPrintFile).use { output ->
                input.copyTo(output)
              }
            }

            mainHandler.post {
              val activity = context.findActivity()
              if (activity == null) {
                Toast.makeText(context, "Cannot print: Activity context required", Toast.LENGTH_SHORT).show()
                onFinished?.invoke()
                return@post
              }
              val printManager = activity.getSystemService(Context.PRINT_SERVICE) as? PrintManager
              if (printManager != null) {
                val printAdapter = FilePrintDocumentAdapter(tempPrintFile)
                val printJobName = "Remmi - $sanitizedTitle"
                printManager.print(
                  printJobName,
                  printAdapter,
                  PrintAttributes.Builder()
                    .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                    .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                    .build()
                )
                onFinished?.invoke()
              } else {
                Toast.makeText(context, "Print service is not available on this device", Toast.LENGTH_LONG).show()
                onFinished?.invoke()
              }
            }
          } catch (e: Exception) {
            Log.e(TAG, "Error writing temporary print file", e)
            mainHandler.post {
              Toast.makeText(context, "Printing error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
              onFinished?.invoke()
            }
          }
        },
        { throwable: Throwable? ->
          Log.e(TAG, "Failed to generate print layout from GeckoSession", throwable)
          mainHandler.post {
            Toast.makeText(
              context,
              "Print layout error: ${throwable?.localizedMessage ?: "Unknown error"}",
              Toast.LENGTH_SHORT
            ).show()
            onFinished?.invoke()
          }
        }
      )
    } catch (e: Exception) {
      Log.e(TAG, "Exception during print preparation", e)
      Toast.makeText(context, "Print error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
      onFinished?.invoke()
    }
  }

  private fun saveToDownloads(context: Context, sourceFile: File, fileName: String) {
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val contentValues = ContentValues().apply {
          put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
          put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
          put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Remmi")
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        if (uri != null) {
          resolver.openOutputStream(uri)?.use { output ->
            FileInputStream(sourceFile).use { input ->
              input.copyTo(output)
            }
          }
        }
      } else {
        @Suppress("DEPRECATION")
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val remmiDir = File(downloadsDir, "Remmi").apply { mkdirs() }
        val targetFile = File(remmiDir, fileName)
        FileInputStream(sourceFile).use { input ->
          FileOutputStream(targetFile).use { output ->
            input.copyTo(output)
          }
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "Could not copy to public Downloads: ${e.message}")
    }
  }

  private fun openOrSharePdf(context: Context, pdfFile: File) {
    try {
      val uri: Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        pdfFile
      )

      val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/pdf")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }

      val chooser = Intent.createChooser(intent, "Open or Share PDF").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(chooser)
    } catch (e: Exception) {
      Log.w(TAG, "Unable to launch PDF viewer chooser: ${e.message}")
    }
  }

  private fun sanitizeFilename(name: String): String {
    return name.replace(Regex("[^a-zA-Z0-9_.-]"), "_").take(40)
  }
}

/**
 * Standard Android PrintDocumentAdapter that feeds a PDF file to Android Print Spooler.
 */
class FilePrintDocumentAdapter(private val file: File) : PrintDocumentAdapter() {
  override fun onLayout(
    oldAttributes: PrintAttributes?,
    newAttributes: PrintAttributes?,
    cancellationSignal: CancellationSignal?,
    callback: LayoutResultCallback?,
    extras: Bundle?
  ) {
    if (cancellationSignal?.isCanceled == true) {
      callback?.onLayoutCancelled()
      return
    }

    val info = PrintDocumentInfo.Builder(file.name)
      .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
      .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
      .build()

    callback?.onLayoutFinished(info, newAttributes != oldAttributes)
  }

  override fun onWrite(
    pages: Array<out PageRange>?,
    destination: ParcelFileDescriptor?,
    cancellationSignal: CancellationSignal?,
    callback: WriteResultCallback?
  ) {
    if (cancellationSignal?.isCanceled == true) {
      callback?.onWriteCancelled()
      return
    }

    try {
      FileInputStream(file).use { input ->
        FileOutputStream(destination?.fileDescriptor).use { output ->
          input.copyTo(output)
        }
      }

      if (cancellationSignal?.isCanceled == true) {
        callback?.onWriteCancelled()
      } else {
        callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
      }
    } catch (e: Exception) {
      callback?.onWriteFailed(e.message)
    }
  }

  override fun onFinish() {
    super.onFinish()
    // Clean up temporary print file if needed
    try {
      if (file.exists() && file.name.startsWith("print_job_")) {
        file.delete()
      }
    } catch (_: Exception) {}
  }
}
