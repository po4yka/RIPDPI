package com.poyka.ripdpi.activities

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.os.Build
import androidx.compose.ui.geometry.Rect
import androidx.core.splashscreen.SplashScreen
import androidx.core.splashscreen.SplashScreenViewProvider
import com.poyka.ripdpi.ui.screens.onboarding.OnboardingBadgeAnchor
import com.poyka.ripdpi.ui.theme.DefaultRipDpiMotion
import com.poyka.ripdpi.ui.theme.RipDpiMotion

// The badge is placed in the first frames after the splash screen. If it is not, the splash screen fades out.
private const val BadgePlacementTimeoutMillis = 500L

/**
 * Sets how the splash screen leaves. When the app starts on onboarding, the splash icon moves to the place of the
 * onboarding step badge and shrinks to its size while the splash screen fades, so the icon becomes the badge.
 *
 * Android 12 and later must know about the listener before the activity resumes, which is before the start
 * destination is known. The listener therefore also owns the exit for every other start destination: a short
 * fade on API 31 and later in place of the system exit animation, and a cut below API 31, as before.
 * With animations off the splash screen is removed at once.
 */
internal fun SplashScreen.setRipDpiExit(
    badgeAnchor: OnboardingBadgeAnchor,
    startsOnOnboarding: () -> Boolean,
) {
    setOnExitAnimationListener { splash ->
        when {
            !ValueAnimator.areAnimatorsEnabled() -> splash.remove()
            startsOnOnboarding() -> splash.carryIconTo(badgeAnchor)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> splash.fadeOut()
            else -> splash.remove()
        }
    }
}

private fun SplashScreenViewProvider.carryIconTo(badgeAnchor: OnboardingBadgeAnchor) {
    val bounds = badgeAnchor.bounds
    if (bounds != null) {
        moveIconTo(bounds)
        return
    }
    // The first onboarding frame can come after the splash screen starts to leave.
    badgeAnchor.onPlaced = { badgeAnchor.bounds?.let(::moveIconTo) ?: fadeOut() }
    view.postDelayed({
        if (badgeAnchor.onPlaced != null) {
            badgeAnchor.onPlaced = null
            fadeOut()
        }
    }, BadgePlacementTimeoutMillis)
}

private fun SplashScreenViewProvider.moveIconTo(badge: Rect) {
    // A splash screen with no icon has no icon view on API 31 and later.
    val icon = runCatching { iconView }.getOrNull()
    if (icon == null || icon.width == 0 || badge.isEmpty) {
        fadeOut()
        return
    }
    val origin = IntArray(2).also(icon::getLocationInWindow)
    val duration = DefaultRipDpiMotion.signatureDurationMillis.toLong()
    // The glyph takes about half of the splash icon view, as the badge icon takes half of the badge.
    val scale = badge.width / icon.width
    icon
        .animate()
        .translationX(badge.center.x - (origin[0] + icon.width / 2f))
        .translationY(badge.center.y - (origin[1] + icon.height / 2f))
        .scaleX(scale)
        .scaleY(scale)
        .setInterpolator(EmphasizedDecelerate)
        .setDuration(duration)
    // The splash screen fades in the second half, so the icon arrives before the badge shows through it.
    view
        .animate()
        .alpha(0f)
        .setStartDelay(duration / 2)
        .setDuration(duration / 2)
        .setListener(removeAtEnd())
}

private fun SplashScreenViewProvider.fadeOut() {
    view
        .animate()
        .alpha(0f)
        .setDuration(DefaultRipDpiMotion.stateDurationMillis.toLong())
        .setListener(removeAtEnd())
}

// An end listener also runs after a cancel, so the splash screen cannot stay on the screen.
private fun SplashScreenViewProvider.removeAtEnd() =
    object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) = remove()
    }

private val EmphasizedDecelerate = TimeInterpolator { RipDpiMotion.EmphasizedDecelerate.transform(it) }
