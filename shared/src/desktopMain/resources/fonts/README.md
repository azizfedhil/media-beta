# Archivo

Static files, generated from the Archivo variable fonts at width 100 (normal):

    Archivo-Regular.ttf   Archivo-Medium.ttf   Archivo-SemiBold.ttf
    Archivo-Bold.ttf      Archivo-ExtraBold.ttf  Archivo-Black.ttf
    Archivo-Italic.ttf    (regular weight; the only italic the app uses)

The same files live in two places, because desktop reads JVM resources and Android reads assets:

    shared/src/desktopMain/resources/fonts/
    androidApp/src/main/assets/fonts/

Keep the two folders identical. The app falls back to the system font for any file that is missing.

Archivo is licensed under the SIL Open Font License 1.1: keep its `OFL.txt` next to the fonts and list it in the About
screen.
