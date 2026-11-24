package app.seb3thehacker.gearslip.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DevFeedTest {

    @Test
    fun picksTheSelectedRefWhenItsCommitChanged() {
        val arm = DevFeed.Asset("Gearslip-arm64-v8a-dev-ba4374e.apk", "https://example/arm.apk", 1234, "abc")
        val feed = DevFeed.Feed(
            listOf(
                DevFeed.Build("main", "ba4374e", "0.5.0-alpha+4.ba4374e", listOf(arm)),
                DevFeed.Build("pr-12", "cafe123", "0.5.0-alpha+5.cafe123", emptyList()),
            ),
        )

        val chosen = DevFeed.select(feed, "main", "5e6c76e", listOf("arm64-v8a"))
        check(chosen != null)
        assertEquals("ba4374e", chosen.sha)
        assertEquals(listOf(arm), chosen.assets)
    }

    @Test
    fun noUpdateForTheSameCommitAnUnknownRefOrAnUnverifiedAsset() {
        val build = DevFeed.Build(
            "main", "ba4374e", "0.5.0-alpha",
            listOf(DevFeed.Asset("Gearslip-arm64-v8a-dev-ba4374e.apk", "https://example/arm.apk", 1, null)),
        )
        val feed = DevFeed.Feed(listOf(build))
        assertNull(DevFeed.select(feed, "main", "ba4374e", listOf("arm64-v8a")))
        assertNull(DevFeed.select(feed, "pr-99", "5e6c76e", listOf("arm64-v8a")))
        assertNull(DevFeed.select(feed, "main", "5e6c76e", listOf("arm64-v8a")))
    }

    @Test
    fun picksTheAbiAssetThenFallsBackToUniversal() {
        val names = listOf(
            "Gearslip-x86-dev-aaa.apk",
            "Gearslip-arm64-v8a-dev-bbb.apk",
            "Gearslip-universal-dev-ccc.apk",
        )
        assertEquals(
            "Gearslip-arm64-v8a-dev-bbb.apk",
            DevFeed.pickAsset(names, listOf("arm64-v8a", "armeabi-v7a")),
        )
        assertEquals(
            "Gearslip-x86-dev-aaa.apk",
            DevFeed.pickAsset(names, listOf("riscv64", "x86")),
        )
        assertEquals(
            "Gearslip-universal-dev-ccc.apk",
            DevFeed.pickAsset(names, listOf("riscv64")),
        )
        assertNull(DevFeed.pickAsset(listOf("notes.txt"), listOf("arm64-v8a")))
    }
}
