package com.lanrhyme.micyou

import androidx.lifecycle.LifecycleOwner

object LifecycleOwnerHolder {
    @Volatile
    private var owner: LifecycleOwner? = null

    fun set(owner: LifecycleOwner) {
        this.owner = owner
    }

    fun get(): LifecycleOwner? = owner
}
