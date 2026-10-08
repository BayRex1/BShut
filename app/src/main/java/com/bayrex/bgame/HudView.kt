package com.bayrex.bgame

import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

class HudView(context: android.content.Context, private val game: GameRenderer) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pulse = 0f

    init { setLayerType(View.LAYER_TYPE_SOFTWARE, null) }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat(); val h = height.toFloat()
        pulse += 0.03f

        when (game.state) {
            GameRenderer.State.MENU -> drawMenu(c,w,h)
            GameRenderer.State.LOADING -> drawLoading(c,w,h)
            GameRenderer.State.DRIVE -> drawDrive(c,w,h)
            GameRenderer.State.CRASH -> drawCrash(c,w,h)
        }
        postInvalidateDelayed(33)
    }

    private fun drawMenu(c:Canvas,w:Float,h:Float) {
        paint.typeface=Typeface.create("sans",Typeface.BOLD)
        paint.textAlign=Paint.Align.LEFT
        paint.textSize=min(w,h)*0.075f
        paint.color=Color.WHITE
        paint.setShadowLayer(12f,0f,3f,Color.BLACK)
        c.drawText("BGAME", w*0.055f,h*0.13f,paint)
        paint.textSize=min(w,h)*0.025f
        paint.alpha=210
        c.drawText("TACTICAL SURVIVAL",w*0.058f,h*0.175f,paint)
        paint.alpha=255
        val bw=w*0.27f; val bh=h*0.14f; val x=w-bw-w*0.055f; val y=h-bh-w*0.055f
        paint.style=Paint.Style.FILL; paint.color=Color.rgb(225,165,42)
        c.drawRoundRect(x,y,x+bw,y+bh,22f,22f,paint)
        paint.color=Color.WHITE; paint.textAlign=Paint.Align.CENTER; paint.textSize=bh*0.34f
        c.drawText("ИГРАТЬ",x+bw/2,y+bh*0.62f,paint)
        paint.textAlign=Paint.Align.LEFT
        paint.textSize=min(w,h)*0.018f; paint.alpha=175
        c.drawText("ПРЕ-АЛЬФА 0.1 • 3D MOBILE",w*0.055f,h*0.94f,paint)
        paint.alpha=255; paint.clearShadowLayer()
    }

    private fun drawLoading(c:Canvas,w:Float,h:Float) {
        paint.color=Color.argb(215,0,0,0); c.drawRect(0f,0f,w,h,paint)
        paint.color=Color.WHITE; paint.textAlign=Paint.Align.CENTER
        paint.typeface=Typeface.create("sans",Typeface.BOLD); paint.textSize=min(w,h)*0.055f
        c.drawText("ЗАГРУЗКА",w/2,h*0.48f,paint)
        paint.typeface=Typeface.DEFAULT; paint.textSize=min(w,h)*0.022f
        c.drawText("Подготовка леса и экипировки…",w/2,h*0.55f,paint)
        val progress=((pulse*0.7f)%1f); paint.color=Color.rgb(225,165,42)
        c.drawRect(w*.32f,h*.61f,w*(.32f+.36f*progress),h*.625f,paint)
    }

    private fun drawDrive(c:Canvas,w:Float,h:Float) {
        paint.color=Color.argb(210,0,0,0); paint.textAlign=Paint.Align.CENTER
        paint.textSize=min(w,h)*0.024f
        val t=game.gameTime
        val line=when {
            t<8f -> "Водитель: Дорога должна быть здесь…"
            t<16f -> "Напарница: Красивый лес. Только не отвлекайся."
            t<24f -> "Водитель: Спокойно, я всё контролирую."
            else -> "Напарница: Смотри вперёд!"
        }
        val boxW=w*.58f; val boxH=h*.105f
        paint.color=Color.argb(175,0,0,0); c.drawRoundRect((w-boxW)/2,h*.78f,(w+boxW)/2,h*.78f+boxH,18f,18f,paint)
        paint.color=Color.WHITE; c.drawText(line,w/2,h*.78f+boxH*.61f,paint)
        paint.textAlign=Paint.Align.LEFT; paint.textSize=min(w,h)*.018f; paint.alpha=160
        c.drawText("ДЕНЬ • ЛЕСНОЙ МАРШРУТ",w*.04f,h*.08f,paint)
        paint.alpha=255
    }

    private fun drawCrash(c:Canvas,w:Float,h:Float) {
        paint.color=Color.BLACK; c.drawRect(0f,0f,w,h,paint)
        paint.color=Color.WHITE; paint.textAlign=Paint.Align.CENTER
        paint.typeface=Typeface.create("sans",Typeface.BOLD); paint.textSize=min(w,h)*.05f
        if(game.gameTime<4f) c.drawText("…",w/2,h/2,paint)
        else { paint.textSize=min(w,h)*.03f; paint.typeface=Typeface.DEFAULT; c.drawText("СВЯЗЬ ПОТЕРЯНА",w/2,h/2,paint) }
    }

    override fun onTouchEvent(e:MotionEvent):Boolean {
        if(e.action==MotionEvent.ACTION_UP && game.state==GameRenderer.State.MENU) {
            val w=width.toFloat(); val h=height.toFloat(); val bw=w*.27f; val bh=h*.14f
            val x=w-bw-w*.055f; val y=h-bh-w*.055f
            if(e.x in x..x+bw && e.y in y..y+bh) { game.start(); return true }
        }
        return true
    }
}
