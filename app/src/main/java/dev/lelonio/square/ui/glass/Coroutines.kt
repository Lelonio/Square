// Replaces the Backdrop catalog's `expect suspend fun awaitFrame()`, which only
// compiles in a multiplatform project. This app is Android-only, so it calls the
// coroutines-android implementation the catalog's Android source set uses.
//
// From https://github.com/Kyant0/AndroidLiquidGlass (Apache-2.0);
// see LICENSE-backdrop.txt.

package dev.lelonio.square.ui.glass

/** Suspends until the next frame is drawn. */
suspend fun awaitFrame() = kotlinx.coroutines.android.awaitFrame()
