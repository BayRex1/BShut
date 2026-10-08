package com.bayrex.bgame

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout

class MainActivity : Activity() {
    private lateinit var game: GameRenderer
    private lateinit var hud: HudView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        game = GameRenderer(this)
        hud = HudView(this, game)
        val root = FrameLayout(this)
        root.addView(game.glView)
        root.addView(hud)
        setContentView(root)
    }

    override fun onResume() { super.onResume(); game.glView.onResume() }
    override fun onPause() { game.glView.onPause(); super.onPause() }
}
