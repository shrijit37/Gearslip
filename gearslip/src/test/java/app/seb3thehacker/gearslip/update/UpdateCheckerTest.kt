package app.seb3thehacker.gearslip.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun versionOrderRespectsNumbersStageAndBuildSuffix() {
        with(UpdateChecker) {
            assert(isNewer("0.4.0-alpha", "0.3.40-alpha"))
            assert(isNewer("0.3.9-alpha", "0.3.10-alpha") == false)
            assert(isNewer("0.3.40-beta", "0.3.40-alpha"))
            assert(isNewer("0.3.40", "0.3.40-beta"))
            // Post-tag builds carry "+N.sha"; more commits since the tag means newer.
            assert(isNewer("0.4.0-alpha+31.1a2b3c4", "0.4.0-alpha+30.5e6c76e"))
            assert(isNewer("0.4.0-alpha+30.5e6c76e", "0.4.0-alpha+30.5e6c76e") == false)
            assertEquals("0.4.0-alpha+30.5e6c76e", normalizeTag("v0.4.0-alpha-30-g5e6c76e"))
            assertEquals("0.4.0-alpha", normalizeTag("v0.4.0-alpha"))
        }
    }

    @Test
    fun picksTheFirstSupportedAbiThenFallsBackToUniversal() {
        val assets = listOf(
            "Gearslip-arm64-v8a-debug.apk",
            "Gearslip-x86-debug.apk",
            "Gearslip-universal-debug.apk",
        )
        assertEquals(
            "Gearslip-arm64-v8a-debug.apk",
            UpdateChecker.pickAsset(assets, listOf("arm64-v8a", "armeabi-v7a")),
        )
        assertEquals(
            "Gearslip-x86-debug.apk",
            UpdateChecker.pickAsset(assets, listOf("riscv64", "x86")),
        )
        assertEquals(
            "Gearslip-universal-debug.apk",
            UpdateChecker.pickAsset(assets, listOf("riscv64")),
        )
        assertNull(UpdateChecker.pickAsset(listOf("Gearslip-debug.apk"), listOf("arm64-v8a")))
    }

    @Test
    fun newestFromSkipsDraftsAndReleasesWithoutAnApkForThisPhone() {
        fun asset(name: String, url: String, size: Long = 1, hash: String? = null) =
            UpdateChecker.Candidate.Asset(name, url, size, hash)
        fun candidate(tag: String, draft: Boolean = false, assets: List<UpdateChecker.Candidate.Asset>) =
            UpdateChecker.Candidate(tag, draft, "https://example/release", assets)

        val state = UpdateChecker.newestFrom(
            listOf(
                candidate("v0.5.0-alpha", draft = true, assets = listOf(asset("Gearslip-arm64-v8a-debug.apk", "https://example/draft.apk"))),
                candidate("v0.4.0-alpha", assets = listOf(asset("Gearslip-x86-debug.apk", "https://example/x86.apk"))),
                candidate(
                    "v0.3.40-alpha",
                    assets = listOf(
                        asset("Gearslip-arm64-v8a-debug.apk", "https://example/arm.apk", 1234, "abc"),
                        asset("notes.txt", "https://example/notes.txt"),
                    ),
                ),
            ),
            "0.3.0-alpha",
            listOf("arm64-v8a", "armeabi-v7a"),
        )
        check(state is UpdateChecker.State.Available)
        assertEquals("0.3.40-alpha", state.release.version)
        assertEquals("https://example/arm.apk", state.release.apkUrl)
        assertEquals(1234L, state.release.size)
        assertEquals("abc", state.release.sha256)
        assertEquals("https://example/release", state.release.pageUrl)
    }

    @Test
    fun fallsBackToAVerifiedAssetWhenThePreferredAbiLacksADigest() {
        val unverifiedSplit = UpdateChecker.Candidate.Asset(
            "Gearslip-arm64-v8a-debug.apk", "https://example/new.apk", 1, null,
        )
        val release = UpdateChecker.Candidate(
            "v0.5.0-alpha", false, "https://example/release",
            listOf(
                unverifiedSplit,
                UpdateChecker.Candidate.Asset("Gearslip-universal-debug.apk", "https://example/universal.apk", 2, "abc"),
            ),
        )
        val state = UpdateChecker.newestFrom(listOf(release), "0.4.0-alpha", listOf("arm64-v8a"))
        check(state is UpdateChecker.State.Available)
        assertEquals("https://example/universal.apk", state.release.apkUrl)
        assertEquals("abc", state.release.sha256)

        // A release with no digest at all is still skipped: never install unverified bytes.
        val noDigests = release.copy(assets = listOf(unverifiedSplit))
        check(UpdateChecker.newestFrom(listOf(noDigests), "0.4.0-alpha", listOf("arm64-v8a")) is UpdateChecker.State.UpToDate)
    }

    @Test
    fun newestFromIgnoresPrereleasesSoOnlyPromotedReleasesReachStable() {
        fun asset(name: String) = UpdateChecker.Candidate.Asset(name, "https://example/$name", 1, "abc")
        fun candidate(tag: String, prerelease: Boolean) = UpdateChecker.Candidate(
            tag, false, "https://example/$tag", listOf(asset("Gearslip-arm64-v8a-debug.apk")), prerelease,
        )

        val state = UpdateChecker.newestFrom(
            listOf(
                candidate("v0.9.0-alpha", true),
                candidate("dev-builds", true),
                candidate("v0.6.0", false),
            ),
            "0.5.0-alpha",
            listOf("arm64-v8a"),
        )
        check(state is UpdateChecker.State.Available)
        assertEquals("0.6.0", state.release.version)
        assertEquals(UpdateChecker.CHANNEL_STABLE, state.release.channel)
    }

    @Test
    fun newestFromSkipsAPromotedReleaseWithAnOlderVersionCode() {
        val apk = UpdateChecker.Candidate.Asset("Gearslip-arm64-v8a-debug.apk", "https://example/arm.apk", 1, "abc")
        val promoted = UpdateChecker.Candidate(
            "v0.6.0", false, "https://example/release", listOf(apk), false, 100,
        )

        // The version name is newer, but a later dev build (code 101) is installed: installing
        // this frozen stable APK would be a downgrade and Android would refuse it.
        check(
            UpdateChecker.newestFrom(
                listOf(promoted), "0.5.0-alpha+30.abc1234", listOf("arm64-v8a"), 101,
            ) is UpdateChecker.State.UpToDate,
        )
        val state = UpdateChecker.newestFrom(
            listOf(promoted), "0.5.0-alpha+30.abc1234", listOf("arm64-v8a"), 99,
        )
        check(state is UpdateChecker.State.Available)
        assertEquals(100, state.release.versionCode)
    }

    @Test
    fun newestFromReportsUpToDateWhenNothingNewerHasAnApk() {
        val old = UpdateChecker.Candidate(
            "v0.3.0-alpha", false, "https://example/release",
            listOf(UpdateChecker.Candidate.Asset("Gearslip-arm64-v8a-debug.apk", "https://example/old.apk", 1, null)),
        )
        check(UpdateChecker.newestFrom(listOf(old), "0.4.0-alpha", listOf("arm64-v8a")) is UpdateChecker.State.UpToDate)
    }
}
