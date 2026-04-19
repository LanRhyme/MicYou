package com.lanrhyme.micyou

import kotlinx.coroutines.CoroutineScope

expect fun openPluginFileChooser(scope: CoroutineScope, onResult: (String?) -> Unit)
