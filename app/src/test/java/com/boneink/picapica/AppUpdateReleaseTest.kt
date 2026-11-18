package com.boneink.picapica

import com.boneink.picapica.update.UpdateException
import com.boneink.picapica.update.selectUpdateRelease
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AppUpdateReleaseTest {
    @Test fun previewSelectsHighestNumericVersionAndItsPreviewApk() {
        val releases = JSONArray()
            .put(release("0.9.0", "debug"))
            .put(release("0.10.0", "debug", "preview", prerelease = true))
            .put(release("0.20.0", "preview", draft = true))
        val update = selectUpdateRelease(releases, "0.8.0-dev", preview = true)!!
        assertEquals("0.10.0", update.version)
        assertEquals("PicaPica-0.10.0-preview.apk", update.url.pathSegments.last())
    }

    @Test fun stableIgnoresPrereleasesAndNeverSelectsAnotherChannel() {
        val releases = JSONArray()
            .put(release("0.13.1", "release"))
            .put(release("0.14.0", "debug"))
            .put(release("0.15.0", "release", prerelease = true))
        assertEquals("0.13.1", selectUpdateRelease(releases, "0.13.0", preview = false)?.version)
        assertEquals("0.14.0", selectUpdateRelease(releases, "0.13.0-preview", preview = true)?.version)
    }

    @Test fun equalAndOlderVersionsAreAlreadyCurrent() {
        val releases = JSONArray().put(release("0.12.1", "debug")).put(release("0.13.0", "preview"))
        assertNull(selectUpdateRelease(releases, "0.13.0-preview", preview = true))
    }

    @Test fun rejectsUnverifiedAssetsAndNonRepositoryLinks() {
        val candidate = release("0.13.1", "preview", "debug")
        candidate.getJSONArray("assets").getJSONObject(0).put("digest", JSONObject.NULL)
        candidate.getJSONArray("assets").getJSONObject(1).put("browser_download_url", "https://example.com/elsewhere.apk")
        assertThrows(UpdateException::class.java) {
            selectUpdateRelease(JSONArray().put(candidate), "0.13.0-preview", preview = true)
        }
    }

    private fun release(version: String, vararg channels: String, prerelease: Boolean = false, draft: Boolean = false): JSONObject {
        val assets = JSONArray()
        for (channel in channels) {
            val name = "PicaPica-$version-$channel.apk"
            assets.put(JSONObject().put("name", name).put("state", "uploaded").put("size", 1000)
                .put("digest", "sha256:${"a".repeat(64)}")
                .put("browser_download_url", "https://github.com/BoneInk/PicaPica/releases/download/v$version/$name"))
        }
        return JSONObject().put("tag_name", "v$version").put("draft", draft).put("prerelease", prerelease).put("assets", assets)
    }
}
