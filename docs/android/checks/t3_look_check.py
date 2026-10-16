"""Model check for T3: the look and the surface, per platform, with videoBlurSupported now true on Android.
playerLook copied from PlayerLogic.kt; sampleable and blurWhilePlaying as PlayerScreen computes them after T2/T3.
The surface is TextureView when sampleable, SurfaceView otherwise (VideoSurface.android.kt)."""
import itertools

def player_look(glass_on, base_flat, moving, blur_while, sampleable):
    if not glass_on: return "Flat"
    if not sampleable: return "Frosted"
    if base_flat: return "Tint"
    if moving and not blur_while: return "Tint"
    return "Blur"

def android(glass_on, liquid, low, base_flat, moving):
    vb, choice = True, True
    sampleable = liquid and vb and not low            # decided once when the player opens
    blur_while = True                                  # hasPlayerGlassChoice
    look = player_look(glass_on, base_flat, moving, blur_while, sampleable)
    surface = "TextureView" if sampleable else "SurfaceView"
    return look, surface

def desktop_before(glass_on, base_flat, moving, blur_playing):
    return player_look(glass_on, base_flat, moving, blur_playing and True, True)

n = 0
rows = {}
for glass_on, liquid, low, base_flat, moving in itertools.product([False, True], repeat=5):
    look, surf = android(glass_on, liquid, low, base_flat, moving)
    if not glass_on:
        assert look == "Flat"
    elif not liquid or low:
        # Frosted default and battery saver: the SurfaceView and the Frosted look, never a blur (T1/T2 unchanged here).
        assert look == "Frosted" and surf == "SurfaceView", (glass_on, liquid, low, base_flat, moving, look, surf)
    else:
        # Liquid Glass on, battery fine: the TextureView, and the controls blur the live picture.
        assert surf == "TextureView"
        assert look == ("Tint" if base_flat else "Blur"), (look, base_flat, moving)
    n += 1
for blur_playing in [False, True]:
    for glass_on, base_flat, moving in itertools.product([False, True], repeat=3):
        assert desktop_before(glass_on, base_flat, moving, blur_playing) == player_look(glass_on, base_flat, moving, blur_playing, True)
print(f"android: {n} cases checked; SurfaceView and Frosted unless Liquid Glass is on with battery OK; then TextureView and Blur")
print("Liquid Glass on, battery OK, playing:", android(True, True, False, False, True))
print("Liquid Glass on, battery saver:      ", android(True, True, True, False, True))
print("Frosted default, playing:            ", android(True, False, False, False, True))
