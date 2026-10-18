# Mural Tracer (Advanced)

Phone camera + AR se apni artwork ko wall par overlay karo aur mural trace karo
(VR mural workflow ka Android version).

## Modes
**AR mode (ARCore)** - image wall par *anchor* ho jaati hai; phone hilne par bhi jagah par rehti hai.
**Simple mode** - sab phones par chalta hai (2D overlay), ARCore ki zaroorat nahi.

## Features
- Gallery se image, opacity slider
- **Outline mode**: Sobel edge detection se photo -> line art (Edge detail slider)
- **Grid** overlay (grid method)
- Lock, Flip, Clear, Torch (Simple mode)
- AR gestures: tap = wall par lagao, drag = move, pinch = size, twist = rotate
- Screen always on

## APK
1. Repo GitHub par push karo
2. **Actions -> Build APK** run hone do, **Artifacts** se APK download karo
3. Release chahiye? `git tag v1.0 && git push --tags` - APK Releases me aa jayegi

## Tips
- ARCore ko texture wali surface chahiye; plain white wall par plane detect hone me time lagta hai.
  Phone ko dheere left-right hilao, ya tap karke image hawa me lagao.
- Outline mode me wall par lines clearly dikhti hain.
- Stable tracing ke liye lock dabane se pehle image align kar lo.
