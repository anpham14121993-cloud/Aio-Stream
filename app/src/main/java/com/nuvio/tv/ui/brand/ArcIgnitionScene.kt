package com.nuvio.tv.ui.brand

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.PathParser
import com.nuvio.tv.R
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Icon4k-native storm convergence and thunder ignition scene on a 1920 x 1080 stage.
 * All geometry, LUTs, paints, and recoloured plates are prepared once; [draw] allocates nothing.
 */
class ArcIgnitionScene private constructor(
    private val logoPlate: Bitmap,
    private val litPlate: Bitmap,
    private val lettering: Bitmap,
    private val palette: ArcPalette,
    /** Reserved for glass plate styling consistency; cutout plates preserve own alpha cleanly. */
    @Suppress("unused")
    private val glassPlate: Boolean,
    private val pixelScale: Float
) {
    /** 2 = full detail, 1 = fewer filaments and sparks, 0 = also no wide storm haze. */
    var detail = 2

    private val cutToStage = Matrix().apply {
        setScale(CutSize / 1024f, CutSize / 1024f)
        postTranslate(CutOriginX, CutOriginY)
    }
    private val triPath = path(PlayTriPath).apply { transform(cutToStage) }

    private val samples = Array(Streams.size) { si ->
        Array(7) { strand ->
            FloatArray(SampleCount * 3).also { out ->
                for (i in 0 until SampleCount) {
                    streamPoint(i / (SampleCount - 1f), Streams[si], strand - 3, out, i * 3)
                }
            }
        }
    }

    private val leaderPoints = buildBolt(
        startX = 410f, startY = -60f,
        endX = TipTLX, endY = TipTLY,
        segments = 16, jitter = 34f, seed = 401
    )
    private val spineUpper = buildBolt(
        startX = TipTLX, startY = TipTLY,
        endX = PeakTRX, endY = PeakTRY,
        segments = 12, jitter = 15f, seed = 509
    )
    private val spineDiag = buildBolt(
        startX = TipTLX, startY = TipTLY,
        endX = TipBRX, endY = TipBRY,
        segments = 20, jitter = 22f, seed = 613
    )
    private val spineLower = buildBolt(
        startX = TroughBLX, startY = TroughBLY,
        endX = TipBRX, endY = TipBRY,
        segments = 12, jitter = 15f, seed = 719
    )
    private val impactForks = arrayOf(
        Branch(0.05f, 1.55f, buildBolt(TipTLX, TipTLY, TipTLX - 115f, TipTLY + 95f, 8, 16f, 801)),
        Branch(0.18f, 1.35f, buildBolt(PeakTRX, PeakTRY, PeakTRX + 110f, PeakTRY - 125f, 9, 18f, 811)),
        Branch(0.45f, 1.40f, buildBolt(CenterX + CircleR, CenterY - 22f, CenterX + CircleR + 140f, CenterY - 90f, 8, 17f, 821)),
        Branch(0.62f, 1.30f, buildBolt(TroughBLX, TroughBLY, TroughBLX - 120f, TroughBLY + 115f, 8, 16f, 831)),
        Branch(0.84f, 1.55f, buildBolt(TipBRX, TipBRY, TipBRX + 125f, TipBRY - 75f, 9, 18f, 841))
    )

    // Precompute bounding layers so hardware layer blits stay tightly cropped around VFX activity.
    private val igniteBounds = RectF(
        LogoX - LightMargin - 140f,
        0f,
        LogoX + LogoSize + LightMargin + 140f,
        LetterTop + 160f
    ).apply { intersect(-1f, -1f, StageW + 1f, StageH + 1f) }

    private val currentsBounds = RectF(igniteBounds).also { r ->
        for (stream in samples) {
            for (pts in stream) {
                for (i in pts.indices step 3) {
                    r.union(pts[i] - LightMargin, pts[i + 1] - LightMargin, pts[i] + LightMargin, pts[i + 1] + LightMargin)
                }
            }
        }
        r.intersect(-1f, -1f, StageW + 1f, StageH + 1f)
    }

    private val tones = HashMap<Int, Int>()
    private fun tone(rgb: Int): Int = tones.getOrPut(rgb) { palette.map(0xFF000000.toInt() or rgb) }

    private val gradients = HashMap<Int, LinearGradient>()
    private val tints = HashMap<Long, PorterDuffColorFilter>()

    private val scratch = FloatArray(512 * 3)
    private val strokePath = Path()
    private val gradientMatrix = Matrix()
    private val src = Rect()
    private val dst = RectF()
    private val bezelRect = RectF()
    private val stageRect = RectF(0f, 0f, StageW, StageH)

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val platePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val layerPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    }
    private val screenGlowPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    }
    private val plainPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val glowSprite = buildGlowSprite()

    private val backdrop = Bitmap.createBitmap(
        max(1, (StageW * pixelScale).roundToInt()),
        max(1, (StageH * pixelScale).roundToInt()),
        Bitmap.Config.ARGB_8888
    ).also {
        val c = Canvas(it)
        c.scale(it.width / StageW, it.height / StageH)
        c.drawColor(Backdrop)
        c.drawRect(0f, 0f, StageW, StageH, backdropGlow(CenterX, CenterY + 12f, 760f, 0.17f, 0x1E4B78))
        c.drawRect(0f, 0f, StageW, StageH, backdropGlow(TipTLX + 80f, TipTLY + 70f, 360f, 0.08f, 0x4EC4FF))
        c.drawRect(0f, 0f, StageW, StageH, backdropGlow(TipBRX - 60f, TipBRY - 60f, 340f, 0.07f, 0x4EC4FF))
        it.prepareToDraw()
    }

    /** Draw one frame stacked on top of [backdrop] at elapsed scene [clock] seconds. */
    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        clock: Float,
        alpha: Float = 1f,
        content: Float = 1f,
        handoff: Boolean = false
    ) {
        if (width <= 0 || height <= 0 || alpha <= 0.002f) return
        val scale = min(width / StageW, height / StageH)
        val originX = (width - StageW * scale) * 0.5f
        val originY = (height - StageH * scale) * 0.5f

        val backdropFits = width <= (StageW * scale + 1f) && height <= (StageH * scale + 1f)
        if (backdropFits) {
            plainPaint.alpha = 255
            dst.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawBitmap(backdrop, null, dst, plainPaint)
        }

        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.translate(originX, originY)
        canvas.scale(scale, scale)
        if (!backdropFits) canvas.drawBitmap(backdrop, null, stageRect, plainPaint)

        // Subtle atmospheric storm pulses beneath the mark before and during strike.
        drawStormBackdropPulses(canvas, clock, content)

        // Draw crisp dark charcoal + chrome cutout plate with standard alpha blending (no washout).
        val plateFade = (ArcStartHandoff.plateFade(clock, handoff) * content * 255).toInt()
        if (plateFade > 0) {
            platePaint.alpha = plateFade
            drawPlate(canvas, logoPlate, platePaint)
        }

        // Electrified litPlate wipes dynamically down the main N-lightning diagonal on strike.
        if (clock >= Ignite) {
            val reveal = clamp((clock - Ignite) / 0.28f)
            val breath = 0.90f + 0.08f * sin((clock - Ignite) * 2.3f)
            val strikeFlash = 0.24f * exp(-max(0f, clock - Ignite) * 4.5f)
            val alphaLit = clamp((breath + strikeFlash) * smooth((clock - Ignite) / 0.045f) * content)
            canvas.save()
            canvas.rotate(-40f, CenterX, CenterY)
            canvas.clipRect(
                CenterX - LogoSize,
                CenterY - LogoSize * 0.72f,
                CenterX + LogoSize,
                CenterY - LogoSize * 0.72f + reveal * (LogoSize * 1.44f)
            )
            canvas.rotate(40f, CenterX, CenterY)
            platePaint.alpha = (alphaLit * 255).toInt()
            drawPlate(canvas, litPlate, platePaint)
            canvas.restore()
        }

        layerPaint.alpha = (content * 255).toInt()
        canvas.saveLayer(if (clock > 3.68f) igniteBounds else currentsBounds, layerPaint)

        val gatherGlow = smooth(clock / 3.4f) * (1f - smooth((clock - 3.66f) / 0.48f))
        if (gatherGlow > 0.005f) {
            glow(canvas, CenterX, CenterY, 260f * (0.4f + 0.6f * gatherGlow), 0.24f * gatherGlow, 0x3AB4FF, fillPaint)
            glow(canvas, TipTLX, TipTLY, 95f * gatherGlow, 0.28f * gatherGlow, 0x8DE2FF, fillPaint)
            glow(canvas, TipBRX, TipBRY, 95f * gatherGlow, 0.28f * gatherGlow, 0x8DE2FF, fillPaint)
        }

        drawFilaments(canvas, clock)
        drawBezelCharge(canvas, clock)
        drawLeaderBolt(canvas, clock)
        drawShockwavesAndBolts(canvas, clock)
        drawSettledPulse(canvas, clock)
        drawWordmark(canvas, clock)

        canvas.restore() // end additive/screen VFX layer

        if (alpha < 0.999f) {
            plainPaint.alpha = (clamp(1f - alpha) * 255).toInt()
            canvas.drawBitmap(backdrop, null, stageRect, plainPaint)
        }
        plainPaint.alpha = 255
        canvas.restoreToCount(layer)
    }

    private fun drawPlate(canvas: Canvas, plate: Bitmap, paint: Paint) {
        placeArt(plate, LogoX, LogoY, LogoSize, LogoSize)
        canvas.drawBitmap(plate, null, dst, paint)
    }

    private fun placeArt(art: Bitmap, x: Float, y: Float, w: Float, h: Float) {
        if (abs(art.width / pixelScale - w) < 0.75f && abs(art.height / pixelScale - h) < 0.75f) {
            dst.set(x, y, x + art.width / pixelScale, y + art.height / pixelScale)
        } else {
            dst.set(x, y, x + w, y + h)
        }
    }

    private fun drawStormBackdropPulses(canvas: Canvas, clock: Float, content: Float) {
        if (detail == 0) return
        val preFlash =
            exp(-((clock - 1.15f) / 0.11f).pow(2)) * 0.16f +
                exp(-((clock - 2.15f) / 0.10f).pow(2)) * 0.22f +
                exp(-((clock - 3.05f) / 0.10f).pow(2)) * 0.30f +
                exp(-((clock - Ignite) / 0.18f).pow(2)) * 0.65f
        val pulse = preFlash * content
        if (pulse > 0.005f) {
            glow(canvas, CenterX - 180f, CenterY - 170f, 540f, pulse * 0.45f, 0x38AEFF, screenGlowPaint)
            glow(canvas, CenterX + 160f, CenterY + 130f, 480f, pulse * 0.35f, 0x62C8FF, screenGlowPaint)
        }
    }

    private fun drawFilaments(canvas: Canvas, clock: Float) {
        val strandFrom = if (detail >= 2) -2 else -1
        val strandTo = if (detail >= 2) 2 else 1
        for (si in Streams.indices) {
            val stream = Streams[si]
            val local = clamp((clock - stream.start) / (stream.end - stream.start))
            if (local <= 0f || local >= 1f) continue
            val head = local.pow(0.76f)
            val tail = max(0f, head - (0.36f * (1f - local) + 0.09f))
            val i0 = (tail * (SampleCount - 1)).toInt()
            val i1 = ((head * (SampleCount - 1)).toInt() + 1).coerceAtMost(SampleCount - 1)
            val n = i1 - i0 + 1
            if (n < 2) continue

            val fade = smooth(local / 0.12f) * (1f - smooth((local - 0.84f) / 0.16f))
            val energy = (0.34f + 0.66f * head) * fade
            for (strand in strandFrom..strandTo) {
                val pts = samples[si][strand + 3]
                val sWidth = if (strand == 0) 2.9f else max(1.1f, 2.0f - abs(strand) * 0.38f)
                val sAlpha = (if (strand == 0) 0.85f else 0.38f / abs(strand)) * energy * stream.depth
                lightStroke(canvas, pts, i0, n, sWidth, sAlpha, stream.hue, hot = strand == 0, bloom = strand == 0)
            }

            // Glowing ionized head + optical glint as each filament rushes into the blade tips.
            val hx = samples[si][3][i1 * 3]
            val hy = samples[si][3][i1 * 3 + 1]
            val headR = 11f + 18f * head
            glow(canvas, hx, hy, headR * 2.1f, 0.24f * energy, stream.hue, fillPaint)
            glow(canvas, hx, hy, headR * 0.45f, 0.72f * energy, 0xF2FBFF, fillPaint)
            pinpoint(canvas, hx, hy, 0.45f * energy, 20f + 16f * head, 0xD6F4FF)
        }
    }

    private fun drawBezelCharge(canvas: Canvas, clock: Float) {
        if (clock < 1.35f || clock > Ignite + 0.32f) return
        val progress = clamp((clock - 1.35f) / (Ignite - 1.35f))
        val fadeOut = 1f - smooth((clock - Ignite) / 0.30f)
        val alpha = smooth((clock - 1.35f) / 0.35f) * fadeOut
        if (alpha <= 0.004f) return

        val radius = mix(CircleR + 28f, CircleR + 2f, smooth(progress))
        bezelRect.set(CenterX - radius, CenterY - radius, CenterX + radius, CenterY + radius)
        val sweep = mix(42f, 155f, progress * progress)
        val spin = clock * (240f + 420f * progress)

        strokePaint.color = tone(0x52C8FF)
        strokePaint.strokeWidth = 6f * alpha
        strokePaint.alpha = (0.36f * alpha * 255).toInt()
        canvas.drawArc(bezelRect, spin, sweep, false, strokePaint)
        canvas.drawArc(bezelRect, spin + 180f, sweep, false, strokePaint)

        strokePaint.color = tone(0xECFAFF)
        strokePaint.strokeWidth = 2.2f
        strokePaint.alpha = (0.85f * alpha * 255).toInt()
        canvas.drawArc(bezelRect, -spin * 1.15f + 45f, sweep * 0.72f, false, strokePaint)
        canvas.drawArc(bezelRect, -spin * 1.15f + 225f, sweep * 0.72f, false, strokePaint)
    }

    private fun drawLeaderBolt(canvas: Canvas, clock: Float) {
        if (clock < 3.36f || clock > 3.92f) return
        val strikeIn = smooth((clock - 3.36f) / 0.14f)
        val fade = 1f - smooth((clock - 3.66f) / 0.24f)
        val count = max(2, (strikeIn * (leaderPoints.size / 2)).toInt())
        if (fade <= 0.005f) return

        packBolt(leaderPoints, count, clock, jitter = 11f, seed = 403)
        lightStroke(canvas, scratch, 0, count, 3.6f, 0.92f * fade, 0x8DE4FF, hot = true, bloom = true)

        val endIdx = (count - 1) * 3
        val ex = scratch[endIdx]
        val ey = scratch[endIdx + 1]
        pinpoint(canvas, ex, ey, 0.72f * fade, 46f, 0xECFAFF)
    }

    private fun drawShockwavesAndBolts(canvas: Canvas, clock: Float) {
        if (clock < Ignite) return
        val elapsed = clock - Ignite
        val impact = exp(-elapsed * 5.2f)
        val mainFade = 1f - smooth((elapsed - 0.18f) / 0.72f)

        // Core flash & expanding storm shockwave rings
        if (elapsed < 0.78f) {
            val waveProgress = clamp(elapsed / 0.65f)
            val ease = 1f - (1f - waveProgress).pow(3)
            val rInner = mix(CircleR * 0.65f, 470f, ease)
            val waveFade = (1f - waveProgress).pow(1.7f)

            strokePaint.color = tone(0x88E0FF)
            strokePaint.strokeWidth = mix(16f, 2f, ease)
            strokePaint.alpha = (0.48f * waveFade * 255).toInt()
            canvas.drawCircle(CenterX, CenterY, rInner, strokePaint)

            strokePaint.color = tone(0xF5FDFF)
            strokePaint.strokeWidth = mix(3.4f, 0.8f, ease)
            strokePaint.alpha = (0.85f * waveFade * 255).toInt()
            canvas.drawCircle(CenterX, CenterY, rInner * 0.92f, strokePaint)

            if (detail >= 1) {
                glow(canvas, CenterX, CenterY, 560f, 0.42f * impact, 0x62CCFF, fillPaint)
                glow(canvas, CenterX, CenterY, 240f, 0.65f * impact, 0xF5FCFF, fillPaint)
            }
        }

        // Main jagged N-lightning discharge + secondary forks across all blade corners
        if (mainFade > 0.005f) {
            val nDiag = spineDiag.size / 2
            packBolt(spineDiag, nDiag, clock, jitter = 13f * (0.4f + impact), seed = 617)
            lightStroke(canvas, scratch, 0, nDiag, 4.4f * (0.5f + 0.5f * mainFade), 0.95f * mainFade, 0x7FDFFF, hot = true, bloom = true)

            val nUpper = spineUpper.size / 2
            packBolt(spineUpper, nUpper, clock, jitter = 8f, seed = 512)
            lightStroke(canvas, scratch, 0, nUpper, 2.8f, 0.78f * mainFade, 0x7FDFFF, hot = true, bloom = true)

            val nLower = spineLower.size / 2
            packBolt(spineLower, nLower, clock, jitter = 8f, seed = 723)
            lightStroke(canvas, scratch, 0, nLower, 2.8f, 0.78f * mainFade, 0x7FDFFF, hot = true, bloom = true)

            val forksCount = if (detail >= 2) impactForks.size else 2
            for (fi in 0 until forksCount) {
                val b = impactForks[fi]
                val bFade = smooth((elapsed - b.at * 0.14f) / 0.04f) * mainFade
                if (bFade <= 0.005f) continue
                val bn = b.points.size / 2
                packBolt(b.points, bn, clock, jitter = 7f, seed = 850 + fi * 17)
                lightStroke(canvas, scratch, 0, bn, b.width, 0.72f * bFade, 0x7FDFFF, hot = true, bloom = detail >= 1)
            }

            // Illuminated Play circular bezel + inner Play triangle perimeter flash
            strokePaint.color = tone(0x7FE0FF)
            strokePaint.strokeWidth = 7.5f * mainFade
            strokePaint.alpha = (0.45f * mainFade * 255).toInt()
            canvas.drawCircle(CenterX, CenterY, CircleR + 2f, strokePaint)

            strokePaint.color = tone(0xF0FBFF)
            strokePaint.strokeWidth = 2.6f
            strokePaint.alpha = (0.90f * mainFade * 255).toInt()
            canvas.drawCircle(CenterX, CenterY, CircleR + 1.5f, strokePaint)

            strokePaint.color = tone(0x88E2FF)
            strokePaint.strokeWidth = 6.5f * (0.35f + 0.65f * impact)
            strokePaint.alpha = (0.42f * mainFade * 255).toInt()
            canvas.drawPath(triPath, strokePaint)

            strokePaint.color = tone(0xF6FDFF)
            strokePaint.strokeWidth = 2.4f
            strokePaint.alpha = (0.92f * mainFade * 255).toInt()
            canvas.drawPath(triPath, strokePaint)

            // Crisp optical star flares on all four blade tips
            pinpoint(canvas, TipTLX, TipTLY, 0.92f * mainFade, 58f, 0x9EE8FF)
            pinpoint(canvas, TipBRX, TipBRY, 0.92f * mainFade, 58f, 0x9EE8FF)
            pinpoint(canvas, PeakTRX, PeakTRY, 0.56f * mainFade, 36f, 0xA8ECFF)
            pinpoint(canvas, TroughBLX, TroughBLY, 0.56f * mainFade, 36f, 0xA8ECFF)

            // Outward radial debris sparks from both acute lightning tips
            val sparks = if (detail >= 2) 14 else 6
            for (i in 0 until sparks) {
                val t = elapsed - i * 0.012f
                if (t <= 0f) continue
                val baseX = if (i % 2 == 0) TipTLX else TipBRX
                val baseY = if (i % 2 == 0) TipTLY else TipBRY
                val ang = if (i % 2 == 0) {
                    2.2f + (hash(i * 5.7) - 0.5f) * 1.4f
                } else {
                    -0.9f + (hash(i * 6.3) - 0.5f) * 1.4f
                }
                val dist = (45f + hash(i * 3.1) * 185f) * (1f - exp(-t * 6.5f))
                val sx = baseX + cos(ang) * dist
                val sy = baseY + sin(ang) * dist
                val sf = (1f - t / 0.72f).coerceIn(0f, 1f) * mainFade
                if (sf > 0.02f) {
                    fillPaint.color = tone(0xE8FAFF)
                    fillPaint.alpha = (sf * 255).toInt()
                    canvas.drawCircle(sx, sy, 2.6f * sf + 0.8f, fillPaint)
                }
            }
        }
    }

    private fun drawSettledPulse(canvas: Canvas, clock: Float) {
        if (clock <= SettleSeconds - 0.25f) return
        val settle = smooth((clock - (SettleSeconds - 0.25f)) / 0.55f)
        val breath = 0.75f + 0.25f * sin(clock * 2.1f)

        // Subtle orbiting plasma arcs around Play bezel
        bezelRect.set(CenterX - CircleR - 2.5f, CenterY - CircleR - 2.5f, CenterX + CircleR + 2.5f, CenterY + CircleR + 2.5f)
        val spin = (clock - Ignite) * 115f
        strokePaint.color = tone(0x70D8FF)
        strokePaint.strokeWidth = 3.0f
        strokePaint.alpha = (0.34f * settle * breath * 255).toInt()
        canvas.drawArc(bezelRect, spin, 48f, false, strokePaint)
        canvas.drawArc(bezelRect, spin + 180f, 48f, false, strokePaint)

        // Traveling highlight core
        strokePaint.color = tone(0xEDF9FF)
        strokePaint.strokeWidth = 1.6f
        strokePaint.alpha = (0.68f * settle * 255).toInt()
        canvas.drawArc(bezelRect, spin + 10f, 22f, false, strokePaint)
        canvas.drawArc(bezelRect, spin + 190f, 22f, false, strokePaint)

        // Subtle shimmering glints at the acute blade tips
        val glintA = (0.35f + 0.30f * sin(clock * 3.1f)) * settle
        val glintB = (0.35f + 0.30f * cos(clock * 2.7f)) * settle
        pinpoint(canvas, TipTLX, TipTLY, glintA * 0.55f, 26f, 0xD8F6FF)
        pinpoint(canvas, TipBRX, TipBRY, glintB * 0.55f, 26f, 0xD8F6FF)
    }

    private fun drawWordmark(canvas: Canvas, clock: Float) {
        if (clock < 3.82f) return
        val reveal = smooth((clock - 3.82f) / 0.58f)
        val wash = smooth((clock - 3.74f) / 0.14f) * (1f - smooth((clock - 3.96f) / 0.72f))
        if (wash > 0.005f) {
            glow(canvas, CenterX, LetterTop + 42f, 220f * wash, 0.28f * wash, 0x7EE0FF, fillPaint)
        }
        val letterH = LetterWidth * lettering.height / lettering.width
        val slideY = (1f - reveal) * 10f
        placeArt(lettering, LetterLeft, LetterTop + slideY, LetterWidth, letterH)
        platePaint.alpha = (reveal * 255).toInt()
        canvas.drawBitmap(lettering, null, dst, platePaint)
    }

    private fun lightStroke(
        canvas: Canvas,
        pts: FloatArray,
        from: Int,
        count: Int,
        width: Float,
        alpha: Float,
        rgb: Int,
        hot: Boolean,
        bloom: Boolean
    ) {
        if (count < 2 || alpha <= 0.002f) return
        val color = tone(rgb)
        strokePath.reset()
        strokePath.moveTo(pts[from * 3], pts[from * 3 + 1])
        for (i in 1 until count) {
            val k = (from + i) * 3
            strokePath.lineTo(pts[k], pts[k + 1])
        }

        val x0 = pts[from * 3]
        val y0 = pts[from * 3 + 1]
        val last = (from + count - 1) * 3
        val x1 = pts[last]
        val y1 = pts[last + 1]
        val length = hypot(x1 - x0, y1 - y0).coerceAtLeast(1f)
        gradientMatrix.reset()
        gradientMatrix.setScale(length, length)
        gradientMatrix.postRotate(Math.toDegrees(kotlin.math.atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())).toFloat())
        gradientMatrix.postTranslate(x0, y0)
        val gradient = gradients.getOrPut(rgb) {
            LinearGradient(
                0f, 0f, 1f, 0f,
                intArrayOf(0x00FFFFFF, withAlpha(color, 255), withAlpha(color, 255), 0x00FFFFFF),
                floatArrayOf(0f, 0.16f, 0.84f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        gradient.setLocalMatrix(gradientMatrix)

        strokePaint.shader = gradient
        if (bloom && detail >= 1) {
            strokePaint.strokeWidth = width * 7.2f
            strokePaint.alpha = (clamp(alpha * 0.22f) * 255).toInt()
            canvas.drawPath(strokePath, strokePaint)
            if (detail >= 2) {
                strokePaint.strokeWidth = width * 3.4f
                strokePaint.alpha = (clamp(alpha * 0.42f) * 255).toInt()
                canvas.drawPath(strokePath, strokePaint)
            }
        }
        strokePaint.strokeWidth = width * 1.45f
        strokePaint.alpha = (clamp(alpha * 0.85f) * 255).toInt()
        canvas.drawPath(strokePath, strokePaint)

        strokePaint.shader = null
        strokePaint.color = if (hot) tone(0xEDF9FF) else color
        strokePaint.strokeWidth = max(0.85f, width * 0.62f)
        strokePaint.alpha = (clamp(alpha) * 255).toInt()
        canvas.drawPath(strokePath, strokePaint)
    }

    private fun backdropGlow(x: Float, y: Float, r: Float, alpha: Float, rgb: Int) = Paint().apply {
        val color = tone(rgb)
        shader = RadialGradient(
            x, y, r,
            intArrayOf(withAlpha(color, (alpha * 255).toInt()), withAlpha(color, (alpha * 0.38f * 255).toInt()), 0),
            floatArrayOf(0f, 0.46f, 1f),
            Shader.TileMode.CLAMP
        )
        isDither = true
    }

    private fun glow(canvas: Canvas, x: Float, y: Float, r: Float, alpha: Float, rgb: Int, paint: Paint) {
        if (alpha <= 0.002f || r <= 0f) return
        if (paint === fillPaint && detail >= 1) sprite(canvas, x, y, r * 2f, alpha * 0.30f, rgb, paint)
        sprite(canvas, x, y, r, alpha, rgb, paint)
    }

    private fun sprite(canvas: Canvas, x: Float, y: Float, r: Float, alpha: Float, rgb: Int, paint: Paint) {
        val color = tone(rgb)
        val previous = paint.colorFilter
        paint.colorFilter = tints.getOrPut(color.toLong() and 0xFFFFFFFFL) {
            PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        }
        paint.alpha = (clamp(alpha) * 255).toInt()
        paint.isFilterBitmap = true
        src.set(0, 0, glowSprite.width, glowSprite.height)
        dst.set(x - r, y - r, x + r, y + r)
        canvas.drawBitmap(glowSprite, src, dst, paint)
        paint.colorFilter = previous
    }

    private fun pinpoint(canvas: Canvas, x: Float, y: Float, strength: Float, size: Float, rgb: Int) {
        if (strength < 0.002f) return
        glow(canvas, x, y, size * 0.48f, strength * 0.85f, rgb, fillPaint)
        fillPaint.color = tone(rgb)
        for (vertical in 0..1) {
            for (k in 0..1) {
                val l = if (k == 0) size * 0.9f else size * 0.46f
                val w = if (k == 0) size * 0.055f else size * 0.025f
                fillPaint.alpha = (clamp((if (k == 0) 0.56f else 0.92f) * strength) * 255).toInt()
                if (vertical == 0) dst.set(x - l, y - w, x + l, y + w) else dst.set(x - w, y - l, x + w, y + l)
                canvas.drawOval(dst, fillPaint)
            }
        }
    }

    private fun packBolt(srcPts: FloatArray, count: Int, clock: Float, jitter: Float, seed: Int) {
        val last = count - 1
        for (i in 0 until count) {
            val u = if (last == 0) 0f else i.toFloat() / last
            val env = sin(PI.toFloat() * u)
            val dx = (fbm(u * 14f + clock * 18f, seed) - 0.5f) * 2f * jitter * env
            val dy = (fbm(u * 14f + clock * 18f, seed + 29) - 0.5f) * 2f * jitter * env
            val k = i * 3
            scratch[k] = srcPts[i * 2] + dx
            scratch[k + 1] = srcPts[i * 2 + 1] + dy
            scratch[k + 2] = 0f
        }
    }

    private class Stream(
        val phase: Float,
        val start: Float,
        val end: Float,
        val seed: Int,
        val hue: Int,
        val depth: Float,
        val targetX: Float,
        val targetY: Float
    )

    private class Branch(val at: Float, val width: Float, val points: FloatArray)

    companion object {
        const val StageW = 1920f
        const val StageH = 1080f
        const val Ignite = 3.7f

        /** The thunder discharge has landed and the mark breathes quietly from here on. */
        const val SettleSeconds = 4.6f

        private const val LogoX = 680f
        private const val LogoY = 160f
        private const val LogoSize = 560f

        // 520x520 cutout inside 640x640 plate leaves 60px halo pad per side.
        private const val CutOriginX = LogoX + (60f / 640f) * LogoSize
        private const val CutOriginY = LogoY + (60f / 640f) * LogoSize
        private const val CutSize = (520f / 640f) * LogoSize

        private const val TipTLX = CutOriginX + (121f / 1024f) * CutSize
        private const val TipTLY = CutOriginY + (53f / 1024f) * CutSize
        private const val PeakTRX = CutOriginX + (586f / 1024f) * CutSize
        private const val PeakTRY = CutOriginY + (210f / 1024f) * CutSize
        private const val CenterX = CutOriginX + (511.5f / 1024f) * CutSize
        private const val CenterY = CutOriginY + (502.5f / 1024f) * CutSize
        private const val CircleR = (216f / 1024f) * CutSize
        private const val TroughBLX = CutOriginX + (437f / 1024f) * CutSize
        private const val TroughBLY = CutOriginY + (809f / 1024f) * CutSize
        private const val TipBRX = CutOriginX + (900f / 1024f) * CutSize
        private const val TipBRY = CutOriginY + (970f / 1024f) * CutSize

        private const val PlayTriPath =
            "M 463 435 C 463 416 476 410 492 419 L 614 492 C 629 501 629 516 614 525 L 492 598 C 476 607 463 601 463 582 Z"

        private const val LetterLeft = 617f
        private const val LetterTop = 735f
        private const val LetterWidth = 686f
        private const val LightMargin = 160f
        private const val SampleCount = 421
        private val Backdrop = Color.rgb(3, 5, 10)

        private val Streams = arrayOf(
            Stream(phase = -2.65f, start = 0.05f, end = 3.74f, seed = 11, hue = 0x5CC8FF, depth = 1.0f, targetX = TipTLX, targetY = TipTLY),
            Stream(phase = -1.55f, start = 0.25f, end = 3.82f, seed = 23, hue = 0x7AE0FF, depth = 0.90f, targetX = PeakTRX, targetY = PeakTRY),
            Stream(phase = -0.35f, start = 0.55f, end = 3.92f, seed = 37, hue = 0x45B4FF, depth = 0.85f, targetX = CenterX, targetY = CenterY),
            Stream(phase = 0.55f, start = 0.10f, end = 3.76f, seed = 53, hue = 0x5CC8FF, depth = 1.0f, targetX = TipBRX, targetY = TipBRY),
            Stream(phase = 1.70f, start = 0.35f, end = 3.86f, seed = 67, hue = 0x7AE0FF, depth = 0.90f, targetX = TroughBLX, targetY = TroughBLY),
            Stream(phase = 2.65f, start = 0.70f, end = 3.98f, seed = 83, hue = 0x8BA6FF, depth = 0.80f, targetX = CenterX, targetY = CenterY)
        )

        /**
         * Decode, resample for the current screen density, and palette-shift scene artwork once
         * off the UI thread.
         */
        fun prepare(
            resources: Resources,
            palette: ArcPalette,
            pixelScale: Float = 1f,
            keepGoing: () -> Boolean = { true }
        ): ArcIgnitionScene {
            val options = BitmapFactory.Options().apply {
                inScaled = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            fun art(id: Int, recolor: Boolean, stageWidth: Float, sharpen: Boolean): Bitmap {
                if (!keepGoing()) throw CancellationException("no longer wanted")
                val decoded = BitmapFactory.decodeResource(resources, id, options)
                val sw = decoded.width
                val sh = decoded.height
                var pixels = IntArray(sw * sh)
                decoded.getPixels(pixels, 0, sw, 0, 0, sw, sh)
                decoded.recycle()
                val tw = (stageWidth * pixelScale).roundToInt()
                var w = sw
                var h = sh
                if (tw in 1 until sw) {
                    w = tw
                    h = max(1, (sh * tw.toFloat() / sw).roundToInt())
                    pixels = ArcPixels.areaResample(pixels, sw, sh, w, h)
                }
                if (recolor && !palette.isOriginal) palette.mapPixels(pixels)
                if (sharpen) ArcPixels.sharpen(pixels, w, h)
                return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).also { it.prepareToDraw() }
            }

            val glass = palette == ArcPalette.Glass
            return ArcIgnitionScene(
                logoPlate = if (glass) {
                    art(R.drawable.arc_intro_logo_plate_glass, recolor = false, LogoSize, sharpen = false)
                } else {
                    art(R.drawable.arc_intro_logo_plate, recolor = true, LogoSize, sharpen = false)
                },
                litPlate = art(R.drawable.arc_intro_lit_plate, recolor = !glass, LogoSize, sharpen = false),
                lettering = art(R.drawable.arc_intro_lettering, recolor = false, LetterWidth, sharpen = true),
                palette = palette,
                glassPlate = glass,
                pixelScale = pixelScale
            )
        }

        private fun path(data: String): Path = PathParser.createPathFromPathData(data)

        private fun buildGlowSprite(): Bitmap {
            val size = 128
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    size * 0.5f, size * 0.5f, size * 0.5f,
                    intArrayOf(0xFFFFFFFF.toInt(), 0xAAFFFFFF.toInt(), 0x2EFFFFFF, 0),
                    floatArrayOf(0f, 0.25f, 0.62f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            Canvas(bitmap).drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
            bitmap.prepareToDraw()
            return bitmap
        }

        private fun buildBolt(
            startX: Float,
            startY: Float,
            endX: Float,
            endY: Float,
            segments: Int,
            jitter: Float,
            seed: Int
        ): FloatArray {
            val pts = FloatArray((segments + 1) * 2)
            val dx = endX - startX
            val dy = endY - startY
            val len = hypot(dx, dy).coerceAtLeast(1f)
            val nx = -dy / len
            val ny = dx / len
            for (i in 0..segments) {
                val u = i.toFloat() / segments
                val env = sin(PI.toFloat() * u)
                val off = (fbm(u * 9.5f, seed) - 0.5f) * 2f * jitter * env
                pts[i * 2] = mix(startX, endX, u) + nx * off
                pts[i * 2 + 1] = mix(startY, endY, u) + ny * off
            }
            return pts
        }

        private fun withAlpha(color: Int, alpha: Int) = (color and 0xFFFFFF) or (alpha shl 24)
        private fun clamp(v: Float, a: Float = 0f, b: Float = 1f) = max(a, min(b, v))
        private fun mix(a: Float, b: Float, u: Float) = a + (b - a) * u
        internal fun smooth(value: Float): Float {
            val v = clamp(value)
            return v * v * (3f - 2f * v)
        }

        private fun fract(n: Float) = n - floor(n)
        private fun fract(n: Double) = n - floor(n)
        private fun hash(n: Double): Float = fract(sin(n * 127.1 + 311.7) * 43758.5453).toFloat()
        private fun noise(x: Float, seed: Int): Float {
            val a = floor(x.toDouble())
            val u = smooth(fract(x))
            return mix(hash(a + seed * 37), hash(a + 1.0 + seed * 37), u)
        }

        private fun fbm(x: Float, s: Int): Float =
            noise(x, s) * 0.62f + noise(x * 2.13f, s + 7) * 0.26f + noise(x * 4.37f, s + 13) * 0.12f

        private fun streamPoint(uIn: Float, s: Stream, strand: Int, out: FloatArray, at: Int) {
            val u = clamp(uIn)
            val v = 1f - u
            val envelope = sin(PI.toFloat() * u)
            val radius = 640f * v.pow(1.22f)
            val angle = s.phase + u * 4.6f + fbm(u * 3.1f, s.seed + 50) * 0.12f * envelope
            val cx = mix(CenterX, s.targetX, u.pow(1.45f))
            val cy = mix(CenterY, s.targetY, u.pow(1.45f))
            val co = cos(angle)
            val si = sin(angle)
            val braid = (strand * 4.4f + fbm(u * 12f, s.seed + strand) * 8.0f) * v.pow(0.45f)
            val turbulence = fbm(u * 30f, s.seed) * 13.5f * envelope + fbm(u * 96f, s.seed + 2) * 3.8f * envelope
            val off = (braid + turbulence) * v.pow(0.24f)
            out[at] = cx + radius * co - si * off
            out[at + 1] = cy + radius * si * 0.62f + co * off
            out[at + 2] = 0f
        }
    }
}
