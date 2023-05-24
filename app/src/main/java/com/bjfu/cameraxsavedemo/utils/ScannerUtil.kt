package com.bjfu.cameraxsavedemo.utils


//noinspection SuspiciousImport
import android.R
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

object ScannerUtil {

    private const val TAG: String = "TTZZ"

    // 首先保存图片
    fun saveImageToGallery(
        context: Context, bitmap: Bitmap, type: ScannerType, isLabel: Boolean
    ): String {
        /*
        注意： build.gradle中的'targetSdk'值：
        'targetSdk 30'时：手机设置里的权限管理中'存储空间 => 访问图片、视频、音频文件',
            Environment.getExternalStorageDirectory()过期失效，需要使用Context.getExternalFilesDir("")
            'getExternalFilesDir()'的文件目录为 '/storage/emulated/0/Android/data/包名/files/Media/'
        'targetSdk 29'时：手机设置里的权限管理中'存储空间 => 访问所有类型文件'
            Environment.getExternalStorageDirectory() 文件目录为 '/storage/emulated/0/'
        */
        val appDir = File(Environment.getExternalStorageDirectory().absolutePath, "Android/data/com.bjfu.segapp/files/Media")
        if (!appDir.exists()) {
            /*目录不存在 则创建*/
            appDir.mkdirs()
        }
        /*下面的CompressFormat.PNG/CompressFormat.JPEG， 这里对应.png/.jpeg*/
        val fileName = if (isLabel) "${System.currentTimeMillis()}.txt" else "${System.currentTimeMillis()}.png"
        Log.e(TAG, "fileName : $fileName")
        val file = File(appDir, fileName)
        try {
            val fos = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos) // 保存bitmap至本地
            fos.flush()
            fos.close()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            if (type == ScannerType.RECEIVER) {
                scannerByReceiver(context, file.absolutePath)
            } else if (type == ScannerType.MEDIA) {
                ScannerByMedia(context, file.absolutePath)
            }
        }
        //返回保存的图片路径
        return file.absolutePath
    }

    /*删除图片*/
    fun deleteSuccess(context: Context, filePath: String) {
        val file = File(filePath)
        file.delete()
//        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
//        val mContentResolver = context.contentResolver
//        val where = MediaStore.Images.Media.DATA + "='" + filePath + "'"
//        //删除图片
//        mContentResolver.delete(uri, where, null)
        Log.e(TAG, "deleteSuccess: $filePath delete true.")
    }

    /*Receiver扫描更新图库图片*/
    private fun scannerByReceiver(context: Context, path: String) {
        context.sendBroadcast(
            Intent(
                Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.parse("file://$path")
            )
        )
        Log.v("TAG", "receiver scanner completed")
    }

    /*MediaScanner 扫描更新图片图片*/
    private fun ScannerByMedia(context: Context, path: String) {
        MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
        Log.v("TAG", "media scanner completed")
    }

    /*扫描的两种方式*/
    enum class ScannerType {
        RECEIVER, MEDIA
    }

    fun saveImageToGallery(context: Context, bmp: Bitmap) {
        // 首先保存图片
        val appDir = File(Environment.getExternalStorageDirectory(), "images") // 得到子目录images的File对象
        if (!appDir.exists()) {
            appDir.mkdir() // 如果不存在此路径文件夹则创建
        }
        val fileName = System.currentTimeMillis().toString() + ".jpg" // 得到文件名，为此时的时间戳
        val file = File(appDir, fileName) // 得到此图片文件的File对象
        try {
            val fos = FileOutputStream(file) // 通过文件输出流将位图的信息出输出到图片文件中
            bmp.compress(Bitmap.CompressFormat.JPEG, 100, fos)
            // 清空文件输出流并关闭
            fos.flush()
            fos.close()
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        }

        // 其次把文件插入到系统图库
        try {
            // 通过insertImage将文件插入到系统图库
            MediaStore.Images.Media.insertImage(
                context.contentResolver,
                file.absolutePath, fileName, null
            )
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
        }
// 最后通知图库更新
        context.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.parse("file://${R.attr.path}")))
    }
}