package dev.s4music.player

/**
 * The showcase screens again with the system font size at its largest (200 %), to catch text that
 * no longer fits. Output goes to build/fontscale/.
 */
class LargeFontScreens : ShowcaseScreens() {
    override val fontScale = 2f
    override val outDir = "build/fontscale"
}
