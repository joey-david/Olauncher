package app.olauncher.helper

import android.animation.ValueAnimator
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.UserHandle
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import app.olauncher.R

/** Full-screen breathing pause shown before an app marked with "Pause" opens. */
class PauseActivity : Activity() {
    private var timer: CountDownTimer? = null
    private var breath: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE) ?: return finish()
        val user = userExtra() ?: android.os.Process.myUserHandle()
        val pause = MindfulPause(this)
        val activityClassName = intent.getStringExtra(EXTRA_ACTIVITY)
        // Packages like Google expose several launcher activities; name the one being opened.
        val label = runCatching {
            val activities = getSystemService(LauncherApps::class.java).getActivityList(packageName, user)
            (activities.firstOrNull { it.componentName.className == activityClassName }?.label
                ?: activities.firstOrNull()?.applicationInfo?.loadLabel(packageManager))?.toString()
        }.getOrNull() ?: packageName
        val attempts = intent.getIntExtra(EXTRA_ATTEMPTS, 1)
        val usage = pause.usageToday(packageName)

        val circle = BreathingCircle(this)
        val prompt = text(getString(R.string.pause_breathe_in), 20f, 0.9f)
        val stats = text(
            buildString {
                append(resources.getQuantityString(R.plurals.pause_attempts, attempts, attempts, label))
                if (usage != null) append(" · ").append(formatUsage(usage))
            },
            14f,
            0.6f,
        )
        val open = button(getString(R.string.pause_open_in, SECONDS)).apply { isEnabled = false; alpha = 0.4f }
        val notNow = button(getString(R.string.pause_not_now))

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.rgb(8, 8, 8))
                setPadding(dp(32), dp(48), dp(32), dp(48))
                addView(text(label, 30f, 1f).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) })
                addView(circle, LinearLayout.LayoutParams(dp(220), dp(220)).apply { topMargin = dp(36); bottomMargin = dp(28) })
                addView(prompt)
                addView(stats, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                        addView(notNow, LinearLayout.LayoutParams(0, -2, 1f))
                        addView(open, LinearLayout.LayoutParams(0, -2, 1f))
                    },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(56) },
                )
            },
        )

        breath = ValueAnimator.ofFloat(0.55f, 1f).apply {
            duration = BREATH_MS
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { circle.scale = it.animatedValue as Float }
            var inhaling = true
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationRepeat(animation: android.animation.Animator) {
                    inhaling = !inhaling
                    prompt.text = getString(if (inhaling) R.string.pause_breathe_in else R.string.pause_breathe_out)
                }
            })
            start()
        }
        timer = object : CountDownTimer(SECONDS * 1000L, 250L) {
            override fun onTick(millisUntilFinished: Long) {
                open.text = getString(R.string.pause_open_in, ((millisUntilFinished + 999) / 1000).toInt())
            }

            override fun onFinish() {
                open.text = getString(R.string.pause_open)
                open.isEnabled = true
                open.animate().alpha(1f).setDuration(300).start()
            }
        }.start()

        notNow.setOnClickListener { finish() }
        open.setOnClickListener {
            pause.markPassed(packageName)
            launch(packageName, activityClassName, intent.getStringExtra(EXTRA_SHORTCUT), user)
            finish()
        }
    }

    override fun onDestroy() {
        timer?.cancel()
        breath?.cancel()
        super.onDestroy()
    }

    private fun launch(packageName: String, activityClassName: String?, shortcutId: String?, user: UserHandle) {
        val launcher = getSystemService(LauncherApps::class.java)
        runCatching {
            if (shortcutId != null) {
                launcher.startShortcut(packageName, shortcutId, null, null, user)
            } else {
                val component = activityClassName?.takeIf { it.isNotBlank() }?.let { ComponentName(packageName, it) }
                    ?: launcher.getActivityList(packageName, user).first().componentName
                launcher.startMainActivity(component, user, null, null)
            }
        }.onFailure { showToast(R.string.unable_to_open_app) }
    }

    @Suppress("DEPRECATION")
    private fun userExtra(): UserHandle? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_USER, UserHandle::class.java) else intent.getParcelableExtra(EXTRA_USER)

    private fun text(value: String, size: Float, alpha: Float) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.WHITE)
        this.alpha = alpha
        gravity = Gravity.CENTER
    }

    private fun button(value: String) = text(value, 16f, 1f).apply {
        setPadding(dp(12), dp(16), dp(12), dp(16))
        isClickable = true
        isFocusable = true
        setBackgroundResource(android.R.drawable.list_selector_background)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun formatUsage(ms: Long): String {
        val minutes = ms / 60_000L
        return if (minutes >= 60) getString(R.string.pause_usage_hours, minutes / 60, minutes % 60) else getString(R.string.pause_usage_minutes, minutes)
    }

    private class BreathingCircle(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        var scale = 0.55f
            set(value) {
                field = value
                invalidate()
            }

        override fun onDraw(canvas: Canvas) {
            val radius = minOf(width, height) / 2f
            paint.alpha = 28
            canvas.drawCircle(width / 2f, height / 2f, radius, paint)
            paint.alpha = (60 + 120 * (scale - 0.55f) / 0.45f).toInt()
            canvas.drawCircle(width / 2f, height / 2f, radius * scale, paint)
        }
    }

    companion object {
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_ACTIVITY = "activity"
        const val EXTRA_USER = "user"
        const val EXTRA_SHORTCUT = "shortcut"
        const val EXTRA_ATTEMPTS = "attempts"
        private const val SECONDS = 5
        private const val BREATH_MS = 4_000L
    }
}
