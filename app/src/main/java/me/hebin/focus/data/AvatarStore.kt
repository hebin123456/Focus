package me.hebin.focus.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.widget.ImageView
import java.io.File

/**
 * 本地头像存储：统一存 filesDir/avatar.jpg。
 * 两种来源：相册选图（SAF，无需权限）、预置色板（程序化生成圆形字母头像）。
 */
object AvatarStore {

    /** 预置头像配色（深底 + 字母） */
    val presets = listOf(
        0xFFFFC94D.toInt(), // 琥珀
        0xFF67E8F9.toInt(), // 青
        0xFFB794F6.toInt(), // 紫
        0xFFF9A8D4.toInt(), // 粉
        0xFF9AE6B4.toInt(), // 绿
        0xFF93C5FD.toInt()  // 蓝
    )

    fun avatarFile(context: Context): File = File(context.filesDir, "avatar.jpg")

    fun hasAvatar(context: Context): Boolean = avatarFile(context).exists()

    /** 加载头像位图（采样到 ~512px，防大图 OOM），无头像返回 null */
    fun load(context: Context): Bitmap? {
        val f = avatarFile(context)
        if (!f.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 512 || bounds.outHeight / (sample * 2) >= 512) sample *= 2
        return BitmapFactory.decodeFile(
            f.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }

    /** 从相册 URI 保存头像 */
    fun saveFromUri(context: Context, uri: Uri): Boolean = runCatching {
        val tmp = File(context.filesDir, "avatar_tmp")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        } ?: return false
        // 先验证能解码，再落位（防止选到损坏文件把头像搞没）
        val check = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(tmp.absolutePath, check)
        require(check.outWidth > 0)
        tmp.renameTo(avatarFile(context))
    }.getOrDefault(false)

    /** 生成预置头像：深色圆底 + 配色字母（取昵称首字符），存为 avatar.jpg */
    fun savePreset(context: Context, color: Int, nickname: String): Boolean = runCatching {
        val size = 512
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFF171D2E.toInt()
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, p)

        val letter = nickname.trim().take(1).ifEmpty { "F" }
        p.color = color
        p.textSize = size * 0.5f
        p.typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        p.textAlign = Paint.Align.CENTER
        val baseline = size / 2f - (p.descent() + p.ascent()) / 2
        canvas.drawText(letter, size / 2f, baseline, p)

        avatarFile(context).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        true
    }.getOrDefault(false)

    /**
     * 渲染到 ImageView：有头像文件用文件；没有则现场画一个默认的（深底金字母）。
     */
    fun render(context: Context, view: ImageView, nickname: String) {
        val bmp = load(context)
        view.setImageBitmap(bmp ?: defaultAvatar(nickname))
    }

    private fun defaultAvatar(nickname: String): Bitmap {
        val size = 256
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.DKGRAY
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, p)
        p.color = 0xFF9AA3C0.toInt()
        p.textSize = size * 0.5f
        p.typeface = Typeface.DEFAULT_BOLD
        p.textAlign = Paint.Align.CENTER
        val letter = nickname.trim().take(1).ifEmpty { "F" }
        val baseline = size / 2f - (p.descent() + p.ascent()) / 2
        canvas.drawText(letter, size / 2f, baseline, p)
        return bmp
    }
}
