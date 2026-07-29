package com.kyant.backdrop.catalog.utils

suspend fun awaitFrame() {
    kotlinx.coroutines.android.awaitFrame()
}
