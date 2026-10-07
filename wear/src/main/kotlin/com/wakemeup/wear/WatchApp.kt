package com.wakemeup.wear

import android.app.Application

class WatchApp : Application() { val store by lazy { WatchStore(this) } }
