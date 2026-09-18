package android.os

object Build {
    object VERSION { const val SDK_INT = 35 }
    object VERSION_CODES { const val R = 30 }
}
class Looper {
    companion object { fun getMainLooper() = Looper() }
}
class Handler(looper: Looper) {
    fun post(block: Runnable): Boolean { pending.add(block); return true }
    companion object {
        private val pending = ArrayDeque<Runnable>()
        fun drain() { while (pending.isNotEmpty()) pending.removeFirst().run() }
    }
}
