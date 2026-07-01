package app.hellvetica.patches.sankaku

import app.hellvetica.patches.shared.Constants.COMPATIBILITY_SANKAKU
import app.morphe.patcher.patch.rawResourcePatch

/**
 * Patch to disable the "Want unlimited access? Get Sankaku Infinite!" upsell popup
 * in the Sankaku Channel app (com.sankakucomplex.channel.black).
 *
 * Sankaku Channel is a React Native app. Its UI and business logic are compiled
 * into Hermes bytecode at `assets/index.android.bundle`.
 *
 * The popup is controlled by `showPopupUpsell`. Both supported versions start by
 * checking a premium/subscription flag and returning early when that flag is true.
 * This patch changes the `JmpTrue` register operand to point at an environment
 * object that is always truthy, forcing the early-return path.
 *
 * 4.23-rc91:
 * - Function #43041 at file offset 0x00DE772B.
 * - Change `JmpTrue 116, r1` to `JmpTrue 116, r0`.
 *
 * 4.24-rc92:
 * - Function #36745 at file offset 0x01151617.
 * - Change `JmpTrue 112, r4` to `JmpTrue 112, r3`.
 */
@Suppress("unused")
val disableInfinitePopupPatch = rawResourcePatch(
    name = "Disable Infinite Upgrade Popup",
    description = "Disables the \"Want unlimited access? Get Sankaku Infinite!\" " +
            "upsell popup that periodically appears while browsing the app.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SANKAKU)

    execute {
        val bundlePath = "assets/index.android.bundle"

        val rc92TargetPattern = byteArrayOf(
            0x34, 0x03, 0x00,              // GetParentEnvironment r3, 0
            0x3B, 0x04, 0x03, 0x03,        // LoadFromEnvironment r4, r3, 3
            0xB0.toByte(), 0x70, 0x04,     // JmpTrue 112, r4
        )

        val rc92ReplacementPattern = byteArrayOf(
            0x34, 0x03, 0x00,
            0x3B, 0x04, 0x03, 0x03,
            0xB0.toByte(), 0x70, 0x03,     // JmpTrue 112, r3
        )

        val rc91TargetPattern = byteArrayOf(
            0x29, 0x00, 0x00,              // GetEnvironment r0, 0
            0x2E.toByte(), 0x01, 0x00, 0x03, // LoadFromEnvironment r1, r0, 3
            0x90.toByte(), 0x74, 0x01,     // JmpTrue 116, r1
        )

        val rc91ReplacementPattern = byteArrayOf(
            0x29, 0x00, 0x00,
            0x2E.toByte(), 0x01, 0x00, 0x03,
            0x90.toByte(), 0x74, 0x00,     // JmpTrue 116, r0
        )

        val patchTargets = listOf(
            "4.24-rc92" to (rc92TargetPattern to rc92ReplacementPattern),
            "4.23-rc91" to (rc91TargetPattern to rc91ReplacementPattern),
        )

        val bundleFile = get(bundlePath)
        val patched = bundleFile.readBytes()

        var matchIndex = -1
        var replacementPattern: ByteArray? = null
        var matchedVersion = ""

        for ((version, patterns) in patchTargets) {
            val (targetPattern, candidateReplacement) = patterns

            outer@ for (i in 0..(patched.size - targetPattern.size)) {
                for (j in targetPattern.indices) {
                    if (patched[i + j] != targetPattern[j]) continue@outer
                }

                matchIndex = i
                replacementPattern = candidateReplacement
                matchedVersion = version
                break
            }

            if (matchIndex >= 0) break
        }

        require(matchIndex >= 0) {
            "showPopupUpsell function signature not found in $bundlePath. " +
                    "The app may have been updated. Expected one of: " +
                    patchTargets.joinToString("; ") { (version, patterns) ->
                        val (targetPattern, _) = patterns
                        "$version=${targetPattern.joinToString(" ") { "0x%02X".format(it) }}"
                    }
        }

        replacementPattern!!.copyInto(patched, matchIndex)
        println("Patched showPopupUpsell for $matchedVersion at 0x${matchIndex.toString(16)}")

        bundleFile.writeBytes(patched)
    }
}
