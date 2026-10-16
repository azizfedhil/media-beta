"""Model check for T2: playerLook inputs as PlayerScreen passes them, before and after T2.
playerLook is copied from PlayerLogic.kt (rule order). Platform values: desktop (blur yes, no choice), Android (no blur yet, choice)."""
import itertools

def player_look(glass_on, base_flat, video_moving, blur_while_playing, sampleable):
    if not glass_on: return "Flat"
    if not sampleable: return "Frosted"
    if base_flat: return "Tint"
    if video_moving and not blur_while_playing: return "Tint"
    return "Blur"

def before(platform, glass_on, base_flat, moving, blur_playing):
    # PlayerScreen before T2: blurPlaying && videoBlurSupported; videoSampleable = videoBlurSupported
    vb = platform["videoBlur"]
    return player_look(glass_on, base_flat, moving, blur_playing and vb, vb)

def after(platform, liquid, low_battery, glass_on, base_flat, moving, blur_playing):
    vb, choice = platform["videoBlur"], platform["choice"]
    sampleable = (liquid and vb and not low_battery) if choice else vb   # decided once; here evaluated per case
    blur_while = True if choice else (blur_playing and vb)
    return player_look(glass_on, base_flat, moving, blur_while, sampleable)

DESKTOP = {"videoBlur": True, "choice": False}
ANDROID = {"videoBlur": False, "choice": True}   # until T3 sets videoBlurSupported = true
n_desk = n_and = 0
for glass_on, base_flat, moving, blur_playing in itertools.product([False, True], [False, True], [False, True], [False, True]):
    # Desktop: the look must not change at all, whatever the Android-only switch says (it is not read on desktop).
    for liquid, low in itertools.product([False, True], [False, True]):
        b = before(DESKTOP, glass_on, base_flat, moving, blur_playing)
        a = after(DESKTOP, liquid, low, glass_on, base_flat, moving, blur_playing)
        assert a == b, ("desktop changed", glass_on, base_flat, moving, blur_playing, liquid, low, a, b)
        n_desk += 1
    # Android before and after T2 (videoBlurSupported still false): identical, and never Blur or Tint with glass on.
    for liquid, low in itertools.product([False, True], [False, True]):
        b = before(ANDROID, glass_on, base_flat, moving, blur_playing)
        a = after(ANDROID, liquid, low, glass_on, base_flat, moving, blur_playing)
        assert a == b, ("android changed", glass_on, base_flat, moving, blur_playing, liquid, low, a, b)
        if glass_on: assert a == "Frosted", ("android glass on but not Frosted", a)
        n_and += 1
print(f"desktop: look unchanged for {n_desk} input cases; android: unchanged and Frosted-only with glass on, {n_and} cases")
print("Liquid switch on Android today (no effect until T3): liquid=True gives", after(ANDROID, True, False, True, False, True, True))
