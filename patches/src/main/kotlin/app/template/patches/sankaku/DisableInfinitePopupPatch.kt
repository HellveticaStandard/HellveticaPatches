package app.template.patches.sankaku

import app.morphe.patcher.patch.rawResourcePatch
import app.template.patches.shared.Constants.COMPATIBILITY_SANKAKU

/**
 * Patch to disable the "Want unlimited access? Get Sankaku Infinite!" upsell popup
 * in the Sankaku Channel app.
 *
 * ## App architecture
 * Sankaku Channel is a React Native application. Its entire UI and business logic
 * is compiled into a Hermes bytecode file at assets/index.android.bundle.
 * The upsell popup is implemented in JavaScript/Redux inside that bundle.
 *
 * ## How the popup works
 * The popup is gated by a timer stored under the Redux persist key:
 *   "upsellCounting.lastShowBannerUpsellTime"
 * The saga/reducer logic checks this timestamp to decide whether to show the popup.
 * By corrupting this storage key at the bytecode level, the popup's timer check
 * will never succeed, effectively preventing it from appearing.
 *
 * ## Patch strategy
 * Since the Hermes string table stores UTF-8 strings verbatim in the binary,
 * we can locate the target string and replace it with a same-length alternative.
 * Same byte-length replacement is mandatory to keep all Hermes internal offsets valid.
 *
 * Target  (39 bytes): "upsellCounting.lastShowBannerUpsellTime"
 * Replace (39 bytes): "upsellCounting.xxxxxxxXxxxxxxXxxxx_DEAD"
 *
 * This makes the key unmatchable. Any persisted popup timer will be orphaned under
 * the new key and will never be read, so the popup condition always fails.
 *
 * Additionally, we corrupt the companion async-action key to prevent the
 * saga from dispatching the upsell show action:
 *
 * Target  (25 bytes): "checkShowPopupUpsellModal"  → found once in string pool
 * Replace (25 bytes): "checkShowPupupDisabledXXX"
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

        // Patches applied to the Hermes bytecode string table.
        // IMPORTANT: Replacement MUST have the same UTF-8 byte length as the target.
        val patches = listOf(
            // Disables the popup timer key — prevents the countdown from being read.
            "upsellCounting.lastShowBannerUpsellTime" to
                    "upsellCounting.xxxxxxxXxxxxxxXxxxx_DEAD",

            // Corrupts the Redux saga trigger key — prevents the popup saga from firing.
            "checkShowPopupUpsellModal" to
                    "checkShowPupupDisabledXXX",
        )

        // Validate same byte lengths at build time.
        for ((target, replacement) in patches) {
            val targetLen = target.toByteArray(Charsets.UTF_8).size
            val replacementLen = replacement.toByteArray(Charsets.UTF_8).size
            require(targetLen == replacementLen) {
                "Patch replacement byte length mismatch for '$target': " +
                        "target=$targetLen, replacement=$replacementLen"
            }
        }

        // Access the bundle file from the APK context.
        val bundleFile = get(bundlePath)

        val content = bundleFile.readBytes()
        val patched = content.copyOf()
        val patchCounts = mutableMapOf<String, Int>()

        for ((target, replacement) in patches) {
            val targetBytes = target.toByteArray(Charsets.UTF_8)
            val replacementBytes = replacement.toByteArray(Charsets.UTF_8)
            var count = 0
            var i = 0
            while (i <= patched.size - targetBytes.size) {
                var match = true
                for (j in targetBytes.indices) {
                    if (patched[i + j] != targetBytes[j]) {
                        match = false
                        break
                    }
                }
                if (match) {
                    replacementBytes.copyInto(patched, i)
                    count++
                    i += targetBytes.size
                } else {
                    i++
                }
            }
            patchCounts[target] = count
        }

        // Fail loudly if critical strings are not found.
        patchCounts.forEach { (target, count) ->
            if (count == 0) {
                error(
                    "Patch target '$target' was not found in $bundlePath. " +
                            "The app may have been updated — please check if the strings " +
                            "still exist in the new version's Hermes bundle.",
                )
            }
        }

        bundleFile.writeBytes(patched)
    }
}
