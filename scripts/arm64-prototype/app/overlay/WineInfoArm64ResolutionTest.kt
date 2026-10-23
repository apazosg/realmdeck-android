package app.gamenative

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import com.winlator.core.WineInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WineInfoArm64ResolutionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun canonicalPrefixedProtonEntryUsesProfileVersionAndInstallPath() {
        val profile = ContentProfile().apply {
            type = ContentProfile.ContentType.CONTENT_TYPE_PROTON
            verName = "proton-11.0-90624-arm64ec"
            verCode = 1
        }
        val entryId = ContentsManager.getEntryName(profile)
        val manager = mock<ContentsManager>()
        whenever(manager.getProfileByEntryName(entryId)).thenReturn(profile)

        val info = WineInfo.fromIdentifier(context, manager, entryId)

        assertTrue(info.isArm64EC)
        assertEquals("11.0", info.version)
        assertEquals("arm64ec", info.getArch())
        assertEquals(ContentsManager.getInstallDir(context, profile).path, info.path)
    }

    @Test
    fun malformedProfileVersionDoesNotBecomeArm64EC() {
        val profile = ContentProfile().apply {
            type = ContentProfile.ContentType.CONTENT_TYPE_PROTON
            verName = "proton-not-a-version-arm64ec"
            verCode = 1
        }
        val entryId = ContentsManager.getEntryName(profile)
        val manager = mock<ContentsManager>()
        whenever(manager.getProfileByEntryName(entryId)).thenReturn(profile)

        val info = WineInfo.fromIdentifier(context, manager, entryId)

        assertFalse(info.isArm64EC)
        assertEquals(WineInfo.MAIN_WINE_VERSION.arch, info.getArch())
    }
}
