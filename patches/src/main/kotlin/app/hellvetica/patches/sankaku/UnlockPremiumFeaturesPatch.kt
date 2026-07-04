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
 * PreferencesCom (Function #22038, offset 0x00F37805) renders the ads toggle.
 * The component builds the options array for the preference row. For FREE users
 * `isDisabled` is computed as `Not r2, r9` where r9 = useMemo result evaluating
 * the subscription level -- returning false for FREE -- so `!false = true`
 * means the toggle is disabled.
 *
 * TWO patches are applied:
 *
 * 2a. Route all users onto the premium settings path (harmless for logged-in
 *     users; mainly helps guests see the full preference list):
 *     IP 0x294 -> bundle offset 0x00F37A99
 *     Target:  0xB0 0x1F 0x05  (JmpTrue 31, r5)
 *     Replace: 0xAE 0x1F 0x05  (Jmp 31  -> unconditional)
 *
 * 2b. Force isDisabled = false by replacing the Not source register:
 *     `Not r2, r9`  (r9 = useMemo subscription check, false for FREE)
 *     ->  `Not r2, r11` (r11 = onPressHideAd closure, always truthy,
 *                        so !truthy = false -> isDisabled = false).
 *     IP 0x2E4 -> bundle offset 0x00F37AE9
 *     Pattern anchors on the two flanking PutOwnBySlotIdx instructions.
 *
 * --- Feature 3: Remove "GET SANKAKU PLUS" upsell blocks ---
 *
 * 3a. FooterBrowsingLimits (Function #16428, offset 0x00E220DF) renders an
 *     inline "Get Plus" block when subscription_level == 0.
 *     Fix: change JmpFalseLong register from r8 (= sub==0, true for FREE)
 *     to r0 (= LoadConstZero = 0 = always falsy) so the jump always fires
 *     and the block is permanently skipped.
 *     IP 0xA6 -> bundle offset 0x00E22185
 *     Target:  0xB3 0x0B 0x01 0x00 0x00 0x08  (JmpFalseLong 267, r8)
 *     Replace: 0xB3 0x0B 0x01 0x00 0x00 0x00  (JmpFalseLong 267, r0)
 *
 * 3b. getTypeCurrentSubLevelByGGPlay (Function #12010, offset 0x00D9FDC0) is
 *     the utility that returns the orange-card data object:
 *     { title: 'common-title__get-sankaku-plus',
 *       description: 'common-title__upgrade-plan-to-get-benefit',
 *       color: mainOrange, isShowInfiniteIcon: false }
 *     The data drives the "GET SANKAKU PLUS" / "Upgrade plan to get more
 *     benefits" orange card in profile menus.
 *
 *     For FREE users the code path is:
 *       IP 0x75: LoadConstNull r6      (r6 = null)
 *       IP 0x77: JmpTrue 7, r0 -> 0x7E (r0=false for FREE -> fall-through)
 *       IP 0x7A: LoadConstString r6, 'common-title__get-sankaku-plus'
 *     Making the JmpTrue unconditional (Jmp) causes FREE users to jump to
 *     0x7E with r6 = null, so `title` in the returned object is null and the
 *     card component does not render the orange upgrade block.
 *
 *     IP 0x77 -> bundle offset 0x00D9FE37
 *     Pattern: LoadConstNull r6 + JmpTrue 7, r0 + LoadConstString r6, 58067
 *     Target:  0x94 0x06 0xB0 0x07 0x00 0x90 0x06 0xD3 0xE2
 *     Replace: 0x94 0x06 0xAE 0x07 0x00 0x90 0x06 0xD3 0xE2
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

        // -- Patch 2a: Ads toggle path (route all users to premium pref path) ------
        // PreferencesCom, Function #22038 @ 0x00F37805, IP 0x294 -> 0x00F37A99
        //
        // Context includes 4 bytes of the preceding StoreToEnvironment tail.
        //
        // Before: JmpTrue  31, r5  (conditional -- skips disabled path only for premium)
        // After:  Jmp      31       (unconditional -- always takes the premium path)
        val adsPathTarget = byteArrayOf(
            // StoreToEnvironment tail (4 bytes for uniqueness)
            0x37, 0x08.toByte(), 0x09, 0x06,
            // JmpTrue addr8=0x1F(31), reg=r5(0x05)
            0xB0.toByte(), 0x1F, 0x05,
        )
        val adsPathReplacement = byteArrayOf(
            // StoreToEnvironment tail -- unchanged
            0x37, 0x08.toByte(), 0x09, 0x06,
            // Jmp addr8=0x1F(31); 0x05 becomes skipped first byte of next instruction
            0xAE.toByte(), 0x1F, 0x05,
        )

        // -- Patch 2b: Ads isDisabled force-false (the real fix) ------------------
        // PreferencesCom, Function #22038 @ 0x00F37805, IP 0x2E4 -> 0x00F37AE9
        //
        // `Not r2, r9`: r9 = useMemo(subscriptionLevelCheck) = false for FREE
        //   -> r2 = !false = true -> isDisabled = true (toggle disabled)
        // `Not r2, r11`: r11 = onPressHideAd closure = always truthy function
        //   -> r2 = !truthy = false -> isDisabled = false (toggle enabled)
        //
        // Pattern anchors on PutOwnBySlotIdx r6, r11, slot2  (0x2E0)
        //                  + Not r2, r9                       (0x2E4) <- change r9->r11
        //                  + PutOwnBySlotIdx r6, r2, slot3   (0x2E7)
        //                  + remaining context for uniqueness
        val adsNotTarget = byteArrayOf(
            0x52, 0x06, 0x0B.toByte(), 0x02,         // PutOwnBySlotIdx r6, r11, 2
            0x13, 0x02, 0x09,                         // Not r2, r9
            0x52, 0x06, 0x02, 0x03,                   // PutOwnBySlotIdx r6, r2, 3
            0x5A, 0x05, 0x06, 0x01,                   // (next instr for uniqueness)
        )
        val adsNotReplacement = byteArrayOf(
            0x52, 0x06, 0x0B.toByte(), 0x02,         // PutOwnBySlotIdx r6, r11, 2 (unchanged)
            0x13, 0x02, 0x0B.toByte(),               // Not r2, r11  (r11 always truthy -> !truthy=false)
            0x52, 0x06, 0x02, 0x03,                   // PutOwnBySlotIdx r6, r2, 3 (unchanged)
            0x5A, 0x05, 0x06, 0x01,                   // (unchanged)
        )

        // -- Patch 3a: Remove GET SANKAKU PLUS block (browse footer) ----------
        // FooterBrowsingLimits, Function #16428 @ 0x00E220DF, IP 0xA6 -> 0x00E22185
        //
        // Context includes the 4-byte StrictEq instruction immediately before
        // the JmpFalseLong for uniqueness.
        //
        // Before: JmpFalseLong 267, r8  (only skips block when NOT free)
        // After:  JmpFalseLong 267, r0  (r0=0 always falsy -> always skips block)
        val getPlusFooterTarget = byteArrayOf(
            // StrictEq r8(0x08), r8(0x08), r0(0x00) -- 4 bytes preceding JmpFalseLong
            0x17.toByte(), 0x08.toByte(), 0x08.toByte(), 0x00,
            // JmpFalseLong opcode + addr32 LE (267 = 0x0000010B) + reg=r8(0x08)
            0xB3.toByte(), 0x0B.toByte(), 0x01, 0x00, 0x00, 0x08,
        )
        val getPlusFooterReplacement = byteArrayOf(
            // StrictEq -- unchanged
            0x17.toByte(), 0x08.toByte(), 0x08.toByte(), 0x00,
            // JmpFalseLong 267, r0(0x00)  -> always jumps (r0 = LoadConstZero = 0)
            0xB3.toByte(), 0x0B.toByte(), 0x01, 0x00, 0x00, 0x00,
        )

        // -- Patch 3b: Remove GET SANKAKU PLUS orange card (profile/menu) -----
        // getTypeCurrentSubLevelByGGPlay, Function #12010 @ 0x00D9FDC0
        // IP 0x77 -> bundle offset 0x00D9FE37
        //
        // This utility function returns the orange-card data:
        //   { title, description, color, isShowInfiniteIcon }
        // used by account/profile menu components. For FREE users the code path
        // falls through to load `title = 'common-title__get-sankaku-plus'`.
        //
        // Fix: change JmpTrue 7, r0 -> Jmp 7 (unconditional). FREE users then
        // jump to the merge point with r6 = null (title = null), so the calling
        // component sees no title and does not render the orange upgrade card.
        //
        // Pattern: LoadConstNull r6 + JmpTrue 7, r0 + LoadConstString r6, 58067
        //           0x94 0x06      + 0xB0 0x07 0x00  + 0x90 0x06 0xD3 0xE2
        val getPlusCardTarget = byteArrayOf(
            0x94.toByte(), 0x06,                      // LoadConstNull r6
            0xB0.toByte(), 0x07, 0x00,                // JmpTrue addr8=7, r0
            0x90.toByte(), 0x06, 0xD3.toByte(), 0xE2.toByte(), // LoadConstString r6, 58067
        )
        val getPlusCardReplacement = byteArrayOf(
            0x94.toByte(), 0x06,                      // LoadConstNull r6 (unchanged)
            0xAE.toByte(), 0x07, 0x00,                // Jmp addr8=7 -> unconditional
            0x90.toByte(), 0x06, 0xD3.toByte(), 0xE2.toByte(), // LoadConstString r6, 58067 (unchanged)
        )

        val patches = listOf(
            BundlePatch("username StrictNeq -> StrictEq (always editable)", usernameTarget, usernameReplacement),
            BundlePatch("ads JmpTrue -> Jmp (route to premium pref path)", adsPathTarget, adsPathReplacement),
            BundlePatch("ads Not r9 -> r11 (force isDisabled=false)", adsNotTarget, adsNotReplacement),
            BundlePatch("GET PLUS footer JmpFalseLong r8 -> r0 (always hide)", getPlusFooterTarget, getPlusFooterReplacement),
            BundlePatch("GET PLUS card JmpTrue -> Jmp (title=null, no orange card)", getPlusCardTarget, getPlusCardReplacement),
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
