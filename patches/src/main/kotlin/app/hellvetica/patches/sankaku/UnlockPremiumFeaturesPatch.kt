package app.hellvetica.patches.sankaku

import app.hellvetica.patches.shared.Constants.COMPATIBILITY_SANKAKU
import app.morphe.patcher.patch.rawResourcePatch

/**
 * Patch to unlock three premium-gated features in the Sankaku Channel app
 * (com.sankakucomplex.channel.black). All features target the Hermes bytecode
 * bundle at `assets/index.android.bundle`.
 *
 * --- Feature 1: Enable Username Editing ---
 * The profile edit screen (ProfileOverviewCom, Function #22029) computes:
 *   r20 = (subscription_level !== SUBSCRIPTION_LEVEL.FREE)
 * and uses r20 as the `editable` prop for the username TextInput. When r20 is
 * false the field is locked and "Upgrade to Premium to edit your username" is shown.
 *
 * Fix: replace `StrictNeq r20, r5, r4` with `StrictEq r20, r5, r5`.
 * Since any value is strictly equal to itself, r20 is forced to `true`,
 * making the field always editable regardless of subscription level.
 *
 *   Function #22029 "ProfileOverviewCom" (1412 bytes) @ bundle offset 0x00F36881
 *   Instruction IP 0x22A -> bundle offset 0x00F36AAB
 *   Target:  0x19 0x14 0x05 0x04  (StrictNeq r20, r5, r4)
 *   Replace: 0x17 0x14 0x05 0x05  (StrictEq  r20, r5, r5 -> always true)
 *
 * --- Feature 2: Enable "Do not display advertising" toggle ---
 * The settings screen (PreferencesCom, Function #22038) checks whether the user
 * has a paid subscription (via r5 truthy) before deciding if the hide-ads row
 * gets isDisabled=true. The branch:
 *   JmpTrue 0x1F, r5 -> jumps to the premium path if truthy (subscriber)
 * For free users the branch falls through to { isDisabled: true }.
 *
 * Fix: replace JmpTrue (0xB0) with unconditional Jmp (0xAE). The jump target
 * (offset 0x1F) stays the same so we always land on the premium path.
 *
 *   Function #22038 "PreferencesCom" (875 bytes) @ bundle offset 0x00F37805
 *   Instruction IP 0x294 -> bundle offset 0x00F37A99
 *   Target:  0xB0 0x1F 0x05  (JmpTrue 31, r5)
 *   Replace: 0xAE 0x1F 0x05  (Jmp 31 + skipped operand byte)
 *
 * --- Feature 3: Remove "GET SANKAKU PLUS" upsell from dropdown menu ---
 * The footer component (FooterBrowsingLimits, Function #16428) renders a
 * "Get Plus" button when subscription_level == 0. The logic is:
 *   r8 = (subscription_level === 0)   <- true when FREE
 *   JmpFalseLong 267, r8              <- only skips block when NOT free
 *
 * Fix: change register operand from r8 to r0. At this point r0 holds the
 * result of LoadConstZero (value 0, always falsy), so JmpFalseLong 267, r0
 * always jumps -- permanently hiding the GET SANKAKU PLUS promotional block.
 *
 *   Function #16428 "FooterBrowsingLimits" (741 bytes) @ bundle offset 0x00E220DF
 *   Instruction IP 0xA6 -> bundle offset 0x00E22185
 *   Target:  0xB3 0x0B 0x01 0x00 0x00 0x08  (JmpFalseLong 267, r8)
 *   Replace: 0xB3 0x0B 0x01 0x00 0x00 0x00  (JmpFalseLong 267, r0 -> always jump)
 *
 * Verified against 4.24-rc92.
 */
@Suppress("unused")
val unlockPremiumFeaturesPatch = rawResourcePatch(
    name = "Unlock Premium Features",
    description = "Enables username editing, activates the 'Do not display advertising' " +
            "toggle, and removes the 'GET SANKAKU PLUS' upsell block from the " +
            "browsing footer -- without requiring a premium subscription.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SANKAKU)

    execute {
        val bundlePath = "assets/index.android.bundle"

        fun ByteArray.indexOfPattern(pattern: ByteArray): Int {
            outer@ for (i in 0..(size - pattern.size)) {
                for (j in pattern.indices) {
                    if (this[i + j] != pattern[j]) continue@outer
                }
                return i
            }
            return -1
        }

        data class BundlePatch(
            val label: String,
            val target: ByteArray,
            val replacement: ByteArray,
        )

        // -- Patch 1: Username editing ----------------------------------------
        // ProfileOverviewCom, Function #22029 @ 0x00F36881, IP 0x22A -> 0x00F36AAB
        //
        // Context includes the last 4 bytes of the preceding GreaterEq instruction
        // (AC 8B 1D 12 07 06) and then the StrictNeq itself for uniqueness.
        //
        // Before: StrictNeq r20, r5, r4  -> r20 = (subscription_level !== FREE)
        // After:  StrictEq  r20, r5, r5  -> r20 = (r5 === r5) = always true
        val usernameTarget = byteArrayOf(
            // Tail of GreaterEq r24, r7, r6 (last 4 bytes)
            0x1D.toByte(), 0x12.toByte(), 0x07, 0x06,
            // StrictNeq: dst=r20(0x14), src1=r5(0x05), src2=r4(0x04)
            0x19.toByte(), 0x14.toByte(), 0x05, 0x04,
        )
        val usernameReplacement = byteArrayOf(
            // GreaterEq tail -- unchanged
            0x1D.toByte(), 0x12.toByte(), 0x07, 0x06,
            // StrictEq: dst=r20(0x14), src1=r5(0x05), src2=r5(0x05)  -> always true
            0x17.toByte(), 0x14.toByte(), 0x05, 0x05,
        )

        // -- Patch 2: Ads toggle ----------------------------------------------
        // PreferencesCom, Function #22038 @ 0x00F37805, IP 0x294 -> 0x00F37A99
        //
        // Context includes 4 bytes of the preceding StoreToEnvironment tail.
        //
        // Before: JmpTrue  31, r5  (conditional -- skips disabled path only for premium)
        // After:  Jmp      31       (unconditional -- always takes the premium path)
        val adsTarget = byteArrayOf(
            // StoreToEnvironment tail (4 bytes for uniqueness)
            0x37, 0x08.toByte(), 0x09, 0x06,
            // JmpTrue addr8=0x1F(31), reg=r5(0x05)
            0xB0.toByte(), 0x1F, 0x05,
        )
        val adsReplacement = byteArrayOf(
            // StoreToEnvironment tail -- unchanged
            0x37, 0x08.toByte(), 0x09, 0x06,
            // Jmp addr8=0x1F(31); 0x05 becomes skipped first byte of next instruction
            0xAE.toByte(), 0x1F, 0x05,
        )

        // -- Patch 3: Remove GET SANKAKU PLUS block ---------------------------
        // FooterBrowsingLimits, Function #16428 @ 0x00E220DF, IP 0xA6 -> 0x00E22185
        //
        // Context includes the 4-byte StrictEq instruction immediately before
        // the JmpFalseLong for uniqueness.
        //
        // Before: JmpFalseLong 267, r8  (only skips block when NOT free)
        // After:  JmpFalseLong 267, r0  (r0=0 always falsy -> always skips block)
        val getPlusTarget = byteArrayOf(
            // StrictEq r8(0x08), r8(0x08), r0(0x00) -- 4 bytes preceding JmpFalseLong
            0x17.toByte(), 0x08.toByte(), 0x08.toByte(), 0x00,
            // JmpFalseLong opcode + addr32 LE (267 = 0x0000010B) + reg=r8(0x08)
            0xB3.toByte(), 0x0B.toByte(), 0x01, 0x00, 0x00, 0x08,
        )
        val getPlusReplacement = byteArrayOf(
            // StrictEq -- unchanged
            0x17.toByte(), 0x08.toByte(), 0x08.toByte(), 0x00,
            // JmpFalseLong 267, r0(0x00)  -> always jumps (r0 = LoadConstZero = 0)
            0xB3.toByte(), 0x0B.toByte(), 0x01, 0x00, 0x00, 0x00,
        )

        val patches = listOf(
            BundlePatch("username StrictNeq -> StrictEq (always editable)", usernameTarget, usernameReplacement),
            BundlePatch("ads JmpTrue -> Jmp (always enable toggle)", adsTarget, adsReplacement),
            BundlePatch("GET PLUS JmpFalseLong r8 -> r0 (always hide block)", getPlusTarget, getPlusReplacement),
        )

        val bundleFile = get(bundlePath)
        val patched = bundleFile.readBytes()

        val results = patches.map { patch ->
            require(patch.target.size == patch.replacement.size) {
                "Patch '${patch.label}' has mismatched target/replacement sizes"
            }
            patch to patched.indexOfPattern(patch.target)
        }

        val missing = results.filter { (_, idx) -> idx < 0 }
        require(missing.isEmpty()) {
            "unlockPremiumFeaturesPatch: signatures not found in $bundlePath. " +
                    "The app may have been updated. Missing:\n" +
                    missing.joinToString("\n") { (patch, _) ->
                        "  - ${patch.label}: [${
                            patch.target.joinToString(" ") { "0x%02X".format(it) }
                        }]"
                    }
        }

        results.forEach { (patch, index) ->
            patch.replacement.copyInto(patched, index)
            println("Patched '${patch.label}' at bundle offset 0x${index.toString(16).uppercase()}")
        }

        bundleFile.writeBytes(patched)
    }
}
