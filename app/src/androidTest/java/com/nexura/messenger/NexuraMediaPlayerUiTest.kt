package com.nexura.messenger

import android.content.Context
import android.view.LayoutInflater
import android.view.TextureView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NexuraMediaPlayerUiTest {
    @OptIn(UnstableApi::class)
    @Test
    fun videoPlayerLayoutUsesTextureViewAndFitScaling() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val playerView = LayoutInflater.from(context)
            .inflate(R.layout.nexura_video_player, null, false) as PlayerView

        assertTrue("Video surface must be a TextureView for Compose dialog compatibility", playerView.videoSurfaceView is TextureView)
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, playerView.resizeMode)
    }
}
