package app.template.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_EXAMPLE = Compatibility(
        name = "XYZ app",
        packageName = "com.example.app",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xFF0045, // Icon color in Morphe Manager
        targets = listOf(
            // "version = null" means the patch works with the latest app target
            // and is expected to work with all future app targets
            AppTarget(
                version = "2.0.0"
            ),
            AppTarget(
                version = "1.0.2",
            )
        )
    )

    /**
     * Sankaku Channel app (APK variant — "black" flavor).
     * Package name confirmed from BuildConfig: com.sankakucomplex.channel.black
     * Targeted version: 4.23 (versionCode 91 = rc91)
     */
    val COMPATIBILITY_SANKAKU = Compatibility(
        name = "Sankaku App",
        packageName = "com.sankakucomplex.channel.black",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x1B1B2F, // Dark blue from Sankaku branding
        targets = listOf(
            AppTarget(
                version = "4.23",
            )
        )
    )
}
