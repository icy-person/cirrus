package dev.klaiber.cirrus.ui.window

import java.awt.Taskbar
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * The app's mark, for the places a window cannot put it.
 *
 * The icon itself is `icon.png` on the classpath, rendered from the same geometry as Android's
 * `ic_launcher_foreground.xml` — a union of three discs over a stadium base, ink on a pale plate.
 * It travels inside the jar for the same reason the typeface does: a packaged app is launched from
 * wherever the launcher happens to be, and an asset resolved against the working directory is an
 * asset that is missing everywhere except a development run.
 *
 * Loaded once. The image is handed to AWT, which holds its own reference, so caching it here is
 * about not decoding a 1024px PNG twice rather than about lifetime.
 */
internal val appIconImage: BufferedImage? by lazy {
    runCatching {
        AppIconMarker::class.java.getResourceAsStream("/icon.png").use { stream ->
            stream?.let(ImageIO::read)
        }
    }.getOrNull()
}

/** Only here to give [appIconImage] a class whose loader owns the resource. */
private class AppIconMarker

/**
 * Puts the mark in the Dock.
 *
 * `Window(icon = …)` sets the *window's* icon, which is what Windows and most Linux shells show in
 * a taskbar — and what macOS ignores entirely. There the Dock reads the icon out of the
 * application bundle, so a packaged build is served by `iconFile` in the Gradle config and an
 * unpackaged `:desktop:run` has no bundle to read at all. `Taskbar` is the one route that reaches
 * it in both cases, which is why this exists alongside the window icon rather than instead of it.
 *
 * Guarded twice over: the whole API is optional per platform, and setting the image throws rather
 * than returning false where the desktop does not support it. An app that cannot show its icon
 * should still start.
 */
fun applyDockIcon() {
    val image = appIconImage ?: return
    runCatching {
        if (!Taskbar.isTaskbarSupported()) return
        val taskbar = Taskbar.getTaskbar()
        if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) taskbar.iconImage = image
    }
}
