package com.bayrex.bgame

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout

class MainActivity : Activity() {
    private lateinit var game: GameRenderer
    private lateinit var hud: HudView
    private lateinit var filamentMenu: FilamentMenu

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        game = GameRenderer(this)
        filamentMenu = FilamentMenu(this)
        // Filament menu is the top layer; it shows the uploaded girl.glb.
        filamentMenu.surfaceView.visibility = View.VISIBLE
        hud = HudView(this, game)

        val root = FrameLayout(this)
        root.addView(game.glView)
        root.addView(filamentMenu.surfaceView)
        root.addView(hud)
        setContentView(root)

        game.onMenuVisibilityChanged = { visible ->
            runOnUiThread {
                filamentMenu.surfaceView.visibility = if (visible) View.VISIBLE else View.GONE
                if (visible) filamentMenu.start() else filamentMenu.stop()
            }
        }

        filamentMenu.start()
    }

    override fun onResume() {
        super.onResume()
        game.glView.onResume()
        if (game.state == GameRenderer.State.MENU) filamentMenu.start()
    }

    override fun onPause() {
        filamentMenu.stop()
        game.glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        filamentMenu.destroy()
        super.onDestroy()
    }
}
